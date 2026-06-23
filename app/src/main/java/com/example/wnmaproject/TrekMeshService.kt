package com.example.trekmesh

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.trekmesh.db.MessageEntity
import com.example.trekmesh.db.TrekMeshDatabase
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrekMeshService : Service() {

    companion object {
        private const val LOG_TAG = "TrekMeshService"
        private const val NOTIFICATION_CHANNEL_ID = "trekmesh_p2p_channel"
        private const val NOTIFICATION_CHANNEL_ALERT_ID = "trekmesh_alert_channel"
        private const val NOTIFICATION_CHANNEL_RELAY_ID = "trekmesh_relay_channel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ALERT_BASE_ID = 1000
        private const val INITIAL_TTL = 7
        private const val MAX_BUFFER_SIZE = 200
        private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L
        private const val TYPE_MSG = "MSG"
        private const val TYPE_ACK = "ACK"
        private const val TYPE_BROADCAST = "BROADCAST"
        private const val TYPE_SOS_STATUS  = "SOS_STATUS"
        private const val TYPE_RESOLVE_VOTE = "RESOLVE_VOTE"
        private const val TYPE_DELETE_MSG   = "DELETE_MSG"
        private const val TYPE_ENCOUNTERS    = "ENCOUNTERS"
        private const val FIELD_SEP = "" // ASCII Unit Separator

        private const val SCAN_LOW_POWER_MS = 3 * 60 * 1000L // 3 minuti solo BT
        private const val SCAN_HIGH_POWER_MS = 45 * 1000L    // 45 secondi BT + WiFi
        
        private const val BREADCRUMB_INTERVAL_MS = 15 * 60 * 1000L // 15 minuti
        private const val WEATHER_RELAY_INTERVAL_MS = 60 * 60 * 1000L // 1 ora

        private val BEACON_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb") // TrekMesh Heartbeat
    }

    private enum class ScanMode { LOW_POWER, HIGH_POWER }
    private var currentScanMode = ScanMode.LOW_POWER
    private var adaptiveScanJob: kotlinx.coroutines.Job? = null

    private data class MeshMessage(
        val id: String,
        val sender: String,
        val ttl: Int,
        val type: String,       // "INFO" | "SOS"
        val priority: Int,      // 1-3
        val text: String,
        val description: String = "",
        val imagePath: String? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val alt: Double = 0.0,
        val breadcrumbs: String = "" // Formato: lat,lon|lat,lon|...
    ) {
        fun toWireFormat(filePayloadId: Long = 0L): String {
            val geoBlob = "$lat|$lon|$alt|$breadcrumbs"
            val encryptedBlob = CryptoHelper.encrypt("$text$FIELD_SEP$description$FIELD_SEP$geoBlob")
            return "$TYPE_MSG|$id|$sender|$ttl|$type|$priority|$filePayloadId|$encryptedBlob"
        }

        fun toEntity(status: String = "PENDING") =
            MessageEntity(id, sender, ttl, type, priority, text, description, imagePath, lat, lon, alt, status)
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String
    private lateinit var db: TrekMeshDatabase
    private lateinit var imagesDir: File
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var bluetoothManager: BluetoothManager? = null

    private var currentLastLocation: Location? = null
    private var isSosActive = false
    private val seenBeaconIds = mutableMapOf<String, Long>() // id -> lastSeenTimestamp

    private var safetyTimerJob: kotlinx.coroutines.Job? = null
    private var lastBreadcrumbTime = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            currentLastLocation = result.lastLocation
            if (isSosActive) updateBeaconStatus(true)
            
            val now = System.currentTimeMillis()
            if (now - lastBreadcrumbTime >= BREADCRUMB_INTERVAL_MS) {
                lastBreadcrumbTime = now
                saveBreadcrumb(result.lastLocation)
            }
        }
    }

    private fun saveBreadcrumb(loc: Location?) {
        val l = loc ?: return
        serviceScope.launch {
            db.breadcrumbDao().insert(com.example.trekmesh.db.BreadcrumbEntity(
                lat = l.latitude, lon = l.longitude, alt = l.altitude
            ))
            // Pulizia: mantieni solo le briciole delle ultime 24 ore
            db.breadcrumbDao().pruneOld(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                ProtezioneCivileRelay.retryPending(db.pendingAlertDao())
            }
        }
    }

    private val connectedEndpoints = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val connectionRetries = mutableMapOf<String, Int>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val ownMessageIds = mutableSetOf<String>()
    private val seenSosStatuses = mutableSetOf<String>() // "msgId:STATUS"

    // Tracks incoming FILE payloads: payloadId -> temp File (set on receive)
    private val incomingFiles = mutableMapOf<Long, File>()
    // Maps filePayloadId -> messageId so we know which message an incoming file belongs to
    private val pendingFileForMessage = mutableMapOf<Long, String>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var alertNotificationCounter = NOTIFICATION_ALERT_BASE_ID

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(LOG_TAG, "Connessione iniziata da ${connectionInfo.endpointName}")
            TrekMeshBus.emitLog("Connessione in entrata da ${connectionInfo.endpointName}, accetto...")
            BenchmarkLogger.start("discovery:${connectionInfo.endpointName}")
            pendingEndpoints.add(endpointId)
            endpointNames[endpointId] = connectionInfo.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = endpointNames[endpointId] ?: endpointId
                val duplicate = connectedEndpoints.firstOrNull { endpointNames[it] == name }
                if (duplicate != null) {
                    Log.d(LOG_TAG, "Connessione duplicata con $name, scarto $endpointId")
                    connectionsClient.disconnectFromEndpoint(endpointId)
                    endpointNames.remove(endpointId)
                    return
                }
                connectedEndpoints.add(endpointId)
                connectionRetries.remove(endpointId)
                TrekMeshBus.updatePeerCount(connectedEndpoints.size)
                TrekMeshBus.emitLog("Connesso a $name")
                BenchmarkLogger.stop("discovery:$name")
                val loc = currentLastLocation
                val myRole = UserRolePrefs.getRole(this@TrekMeshService) ?: UserRole.HIKER
                // Rifugios log hikers they meet; hikers log everyone except rifugios (uploaded separately)
                if (myRole == UserRole.RIFUGIO || !isRifugioPeer(name)) {
                    EncounterLogger.logEncounter(this@TrekMeshService, name, loc?.latitude, loc?.longitude, loc?.altitude)
                }
                if (myRole == UserRole.HIKER && isRifugioPeer(name)) {
                    Log.d(LOG_TAG, "Peer $name riconosciuto come rifugio, invio encounters")
                    serviceScope.launch { sendEncountersToRifugio(endpointId) }
                }
                serviceScope.launch { flushBufferTo(endpointId) }
            } else {
                val code = resolution.status.statusCode
                val retries = connectionRetries[endpointId] ?: 0
                // Errore 13 (STATUS_ERROR) = race condition P2P_CLUSTER, retry automatico
                if (code == 13 && retries < 3) {
                    connectionRetries[endpointId] = retries + 1
                    Log.d(LOG_TAG, "Errore 13 da ${endpointNames[endpointId]}, retry ${retries + 1}/3...")
                    val service = this@TrekMeshService
                    serviceScope.launch {
                        delay(2000L * (retries + 1))
                        if (endpointId !in connectedEndpoints && endpointId !in pendingEndpoints) {
                            pendingEndpoints.add(endpointId)
                            service.retryConnection(endpointId)
                        }
                    }
                } else {
                    connectionRetries.remove(endpointId)
                    endpointNames.remove(endpointId)
                    TrekMeshBus.emitLog("Connessione fallita (codice: $code)")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            TrekMeshBus.updatePeerCount(connectedEndpoints.size)
            TrekMeshBus.emitLog("Disconnesso da $name")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
        ) {
            if (endpointId in connectedEndpoints || endpointId in pendingEndpoints) return
            if (endpointNames.values.contains(info.endpointName)) return
            TrekMeshBus.emitLog("Dispositivo trovato: ${info.endpointName}, richiedo connessione...")
            pendingEndpoints.add(endpointId)
            endpointNames[endpointId] = info.endpointName
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            // onEndpointLost riguarda il discovery, non la connessione:
            // se il peer è già connesso, la connessione rimane attiva — ignoriamo
            if (endpointId in connectedEndpoints) return
            val name = endpointNames.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            // Logghiamo solo se avevamo effettivamente avviato una connessione con questo peer
            if (name != null) Log.d(LOG_TAG, "Endpoint perso di vista (mai connesso): $name")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = String(payload.asBytes() ?: return, Charsets.UTF_8)
                    when (raw.substringBefore("|")) {
                        TYPE_MSG         -> handleIncomingMessage(endpointId, raw)
                        TYPE_ACK         -> handleIncomingAck(raw)
                        TYPE_SOS_STATUS  -> handleIncomingSosStatus(endpointId, raw)
                        TYPE_RESOLVE_VOTE -> handleResolveVote(endpointId, raw)
                        TYPE_DELETE_MSG  -> handleDeleteMsg(endpointId, raw)
                        TYPE_ENCOUNTERS  -> handleIncomingEncounters(endpointId, raw)
                        else -> Log.w(LOG_TAG, "Tipo sconosciuto da $endpointId")
                    }
                }
                Payload.Type.FILE -> {
                    @Suppress("DEPRECATION")
                    val tempFile = payload.asFile()?.asJavaFile() ?: return
                    incomingFiles[payload.id] = tempFile
                }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) return
            val tempFile = incomingFiles.remove(update.payloadId) ?: return
            val msgId = pendingFileForMessage.remove(update.payloadId) ?: return
            val dest = File(imagesDir, "$msgId.jpg")
            if (tempFile.renameTo(dest)) {
                serviceScope.launch { db.messageDao().updateImagePath(msgId, dest.absolutePath) }
                TrekMeshBus.updateMessageImage(msgId, dest.absolutePath)
            }
        }
    }

    private fun handleIncomingMessage(endpointId: String, raw: String) {
        // Wire: MSG|id|sender|ttl|type|priority|filePayloadId|encryptedBlob
        val parts = raw.split("|", limit = 8)
        if (parts.size < 8) {
            Log.w(LOG_TAG, "MSG malformato da $endpointId")
            return
        }
        val msgId          = parts[1]
        val sender         = parts[2]
        val ttlStr         = parts[3]
        val type           = parts[4]
        val priorityStr    = parts[5]
        val filePayloadIdStr = parts[6]
        val encryptedBlob  = parts[7]
        val ttl = ttlStr.toIntOrNull() ?: 0
        val priority = priorityStr.toIntOrNull() ?: 1
        val filePayloadId = filePayloadIdStr.toLongOrNull() ?: 0L

        val decrypted = CryptoHelper.decrypt(encryptedBlob) ?: run {
            Log.w(LOG_TAG, "Decifratura fallita per $msgId")
            return
        }
        
        val segments = decrypted.split(FIELD_SEP)
        if (segments.size < 2) return
        
        val text = segments[0]
        val description = segments[1]
        
        var lat = 0.0
        var lon = 0.0
        var alt = 0.0
        
        if (segments.size >= 3) {
            val geoParts = segments[2].split("|")
            if (geoParts.size >= 3) {
                lat = geoParts[0].toDoubleOrNull() ?: 0.0
                lon = geoParts[1].toDoubleOrNull() ?: 0.0
                alt = geoParts[2].toDoubleOrNull() ?: 0.0
            }
            // I breadcrumbs sono nel 4° elemento del geoBlob se presenti
            val crumbs = if (geoParts.size >= 4) geoParts[3] else ""
            if (crumbs.isNotEmpty()) {
                TrekMeshBus.emitLog("Ricevuta traccia storica (Breadcrumbs) da $sender")
            }
        }

        if (!seenMessageIds.add(msgId)) {
            Log.d(LOG_TAG, "Messaggio duplicato ignorato: $msgId")
            return
        }

        if (filePayloadId != 0L) pendingFileForMessage[filePayloadId] = msgId

        BenchmarkLogger.log("MSG ricevuto da $sender | tipo=$type ttl=$ttl hops=${initialTtlFor(type) - ttl + 1}")
        val receivedTtl = ttl - 1
        TrekMeshBus.emitMessage(msgId, sender, type, priority, text, description, null, lat, lon, alt, isOwn = false, ttl = receivedTtl)
        serviceScope.launch {
            db.messageDao().insert(MessageEntity(msgId, sender, receivedTtl, type, priority, text, description, null, lat, lon, alt, "RECEIVED"))
        }
        sendAck(endpointId, msgId)
        
        val distanceStr = currentLastLocation?.let { loc ->
            if (lat != 0.0) {
                val results = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
                " (a %.0fm)".format(results[0])
            } else ""
        } ?: ""
        
        showMessageNotification(
            msgId = msgId,
            sender = sender,
            type = type,
            priority = priority,
            text = text + distanceStr,
            description = description,
            ttl = receivedTtl,
            timestamp = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            alt = alt
        )

        if (type == "SOS" && priority >= 3 && UserRolePrefs.getRole(this) == UserRole.RIFUGIO) {
            serviceScope.launch {
                val relayed = ProtezioneCivileRelay.sendAlert(
                    messageId = msgId,
                    sender = sender,
                    type = type,
                    priority = priority,
                    text = text,
                    description = description,
                    rifugioName = localEndpointName,
                    dao = db.pendingAlertDao()
                )
                if (relayed) {
                    TrekMeshBus.emitLog("SOS inoltrato alla Protezione Civile ✓")
                    showRelayConfirmNotification(sender)
                }
            }
        }

        if (ttl > 1) {
            val forward = MeshMessage(msgId, sender, ttl - 1, type, priority, text, description, null, lat, lon, alt)
            serviceScope.launch { persistMessage(forward, "RECEIVED") }
            forwardToAllExcept(forward, excludeEndpointId = endpointId)
        }
    }

    private fun handleIncomingAck(raw: String) {
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return
        val (_, originalMsgId, ackerName) = parts
        if (!ownMessageIds.remove(originalMsgId)) return // ACK per un messaggio non nostro, ignora
        serviceScope.launch { db.messageDao().updateStatus(originalMsgId, "DELIVERED") }
        TrekMeshBus.updateMessageStatus(originalMsgId, "DELIVERED")
        TrekMeshBus.emitLog("Messaggio consegnato a $ackerName")
    }

    private fun handleIncomingSosStatus(endpointId: String, raw: String) {
        // Wire: SOS_STATUS|msgId|status|rifugioName
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) return
        val (_, msgId, status, rifugioName) = parts
        if (!seenSosStatuses.add("$msgId:$status")) return
        serviceScope.launch { db.messageDao().updateStatus(msgId, status) }
        TrekMeshBus.updateMessageStatus(msgId, status)
        val label = when (status) {
            "ACKNOWLEDGED" -> "preso in carico da $rifugioName"
            "RESOLVED"     -> "risolto da $rifugioName"
            else           -> status
        }
        TrekMeshBus.emitLog("SOS $label")
        // Propaga agli altri nodi
        val forward = "$TYPE_SOS_STATUS|$msgId|$status|$rifugioName"
        val payload = Payload.fromBytes(forward.toByteArray(Charsets.UTF_8))
        (connectedEndpoints - endpointId).forEach {
            connectionsClient.sendPayload(it, payload)
        }
    }

    private fun sendAck(endpointId: String, originalMsgId: String) {
        val ack = "$TYPE_ACK|$originalMsgId|$localEndpointName"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(ack.toByteArray(Charsets.UTF_8)))
    }

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        serviceId = packageName
        localEndpointName = generateEndpointName()
        db = TrekMeshDatabase.getInstance(this)
        imagesDir = File(filesDir, "images").also { it.mkdirs() }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        startLocationUpdates()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        serviceScope.launch {
            seenMessageIds += db.messageDao().getAllIds()
            db.messageDao().getAll().forEach { e ->
                if (e.status !in listOf("PENDING", "RECEIVED")) seenSosStatuses.add("${e.id}:${e.status}")
                if (e.status != "RECEIVED") ownMessageIds.add(e.id)
                TrekMeshBus.emitMessage(
                    id = e.id,
                    sender = e.sender,
                    type = e.type,
                    priority = e.priority,
                    text = e.text,
                    description = e.description,
                    imagePath = e.imagePath,
                    lat = e.lat,
                    lon = e.lon,
                    alt = e.alt,
                    isOwn = e.status != "RECEIVED",
                    ttl = e.ttl,
                    timestamp = e.timestamp
                )
                if (e.status !in listOf("PENDING", "RECEIVED")) {
                    TrekMeshBus.updateMessageStatus(e.id, e.status)
                }
            }
        }
        // Retry SOS in coda se la rete era già disponibile all'avvio
        serviceScope.launch { ProtezioneCivileRelay.retryPending(db.pendingAlertDao()) }

        listenForOutgoingMessages()
        listenForSosStatusUpdates()
        listenForResolveVotes()
        listenForDeleteMessages()
        listenForSafetyActions()
        startPeriodicCleanup()
        startBleBeaconing()
        
        if (UserRolePrefs.getRole(this) == UserRole.RIFUGIO) {
            startWeatherRelay()
        }
    }

    private fun startWeatherRelay() {
        serviceScope.launch {
            while (isActive) {
                val weather = WeatherRelay.fetchWeather()
                if (weather != null) {
                    TrekMeshBus.sendMessage(OutgoingMessage(
                        type = TYPE_BROADCAST,
                        priority = 2,
                        text = weather,
                        description = "Aggiornamento automatico Rifugio"
                    ))
                }
                delay(WEATHER_RELAY_INTERVAL_MS)
            }
        }
    }

    private fun handleResolveVote(endpointId: String, raw: String) {
        // Wire: RESOLVE_VOTE|msgId|voterName
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return
        val msgId = parts[1]
        val voterName = parts[2]
        val prefs = getSharedPreferences("trekmesh_votes", MODE_PRIVATE)
        val voters = prefs.getString(msgId, "")!!
            .split(",").filter { it.isNotBlank() }.toMutableSet()
        if (!voters.add(voterName)) return  // voter già contato, non ritrasmettere
        prefs.edit().putString(msgId, voters.joinToString(",")).apply()
        if (voters.size >= 2) {
            serviceScope.launch { db.messageDao().setTtlZero(msgId); db.messageDao().deleteById(msgId) }
            TrekMeshBus.removeMessageFromMemory(msgId)
            val delWire = "$TYPE_DELETE_MSG|$msgId"
            val payload = Payload.fromBytes(delWire.toByteArray(Charsets.UTF_8))
            (connectedEndpoints - endpointId).forEach { connectionsClient.sendPayload(it, payload) }
        } else {
            val forward = "$TYPE_RESOLVE_VOTE|$msgId|$voterName"
            val payload = Payload.fromBytes(forward.toByteArray(Charsets.UTF_8))
            (connectedEndpoints - endpointId).forEach { connectionsClient.sendPayload(it, payload) }
        }
    }

    private fun handleDeleteMsg(endpointId: String, raw: String) {
        val msgId = raw.substringAfter("|")
        serviceScope.launch { db.messageDao().setTtlZero(msgId); db.messageDao().deleteById(msgId) }
        TrekMeshBus.removeMessageFromMemory(msgId)
        val forward = "$TYPE_DELETE_MSG|$msgId"
        val payload = Payload.fromBytes(forward.toByteArray(Charsets.UTF_8))
        (connectedEndpoints - endpointId).forEach { connectionsClient.sendPayload(it, payload) }
    }

    private fun listenForDeleteMessages() {
        serviceScope.launch {
            TrekMeshBus.deleteMessage.collect { msgId ->
                val wire = "$TYPE_DELETE_MSG|$msgId"
                val payload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
                connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
            }
        }
    }

    private fun listenForResolveVotes() {
        serviceScope.launch {
            TrekMeshBus.resolveVotes.collect { msgId ->
                val prefs = getSharedPreferences("trekmesh_votes", MODE_PRIVATE)
                val voters = prefs.getString(msgId, "")!!
                    .split(",").filter { it.isNotBlank() }.toMutableSet()
                voters.add(localEndpointName)
                prefs.edit().putString(msgId, voters.joinToString(",")).apply()
                if (voters.size >= 2) {
                    serviceScope.launch { db.messageDao().setTtlZero(msgId); db.messageDao().deleteById(msgId) }
                    TrekMeshBus.removeMessageFromMemory(msgId)
                    val wire = "$TYPE_DELETE_MSG|$msgId"
                    val payload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
                    connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
                } else {
                    val wire = "$TYPE_RESOLVE_VOTE|$msgId|$localEndpointName"
                    val payload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
                    connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
                }
            }
        }
    }

    private fun listenForSosStatusUpdates() {
        serviceScope.launch {
            TrekMeshBus.sosStatusUpdates.collect { update ->
                serviceScope.launch { db.messageDao().updateStatus(update.msgId, update.status) }
                val wire = "$TYPE_SOS_STATUS|${update.msgId}|${update.status}|${update.rifugioName}"
                val payload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
                connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
            }
        }
    }

    private fun listenForSafetyActions() {
        serviceScope.launch {
            TrekMeshBus.safetyActions.collect { action ->
                when (action) {
                    SafetyTimerAction.START_30M -> startSafetyTimer(30 * 60)
                    SafetyTimerAction.START_1H  -> startSafetyTimer(60 * 60)
                    SafetyTimerAction.START_2H  -> startSafetyTimer(120 * 60)
                    SafetyTimerAction.STOP      -> stopSafetyTimer()
                }
            }
        }
    }

    private fun startSafetyTimer(seconds: Int) {
        safetyTimerJob?.cancel()
        safetyTimerJob = serviceScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                TrekMeshBus.updateSafetyTimer(remaining)
                delay(1000)
                remaining--
            }
            TrekMeshBus.updateSafetyTimer(0)
            triggerSafetyAlarm()
        }
        TrekMeshBus.emitLog("Timer di sicurezza avviato: ${seconds / 60} minuti")
    }

    private fun stopSafetyTimer() {
        safetyTimerJob?.cancel()
        TrekMeshBus.updateSafetyTimer(0)
        TrekMeshBus.emitLog("Timer di sicurezza interrotto ✓")
    }

    private fun triggerSafetyAlarm() {
        TrekMeshBus.emitLog("⚠️ ALLARME: Check-in mancato! Invio SOS automatico...")
        TrekMeshBus.sendMessage(OutgoingMessage(
            type = "SOS",
            priority = 3,
            text = "SOS AUTOMATICO: Mancato check-in programmato"
        ))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
            .setMinUpdateIntervalMillis(10_000)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun startBleBeaconing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "BLUETOOTH_ADVERTISE non concesso, beaconing BLE disabilitato")
            return
        }
        val advertiser = bluetoothManager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(LOG_TAG, "BLE Advertiser non disponibile")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val shortId = localEndpointName.hashCode()
        val statusByte: Byte = if (isSosActive) 1 else 0
        val lat = currentLastLocation?.latitude?.toFloat() ?: 0.0f
        val lon = currentLastLocation?.longitude?.toFloat() ?: 0.0f
        
        val manufacturerData = ByteBuffer.allocate(13)
            .putInt(shortId)
            .put(statusByte)
            .putFloat(lat)
            .putFloat(lon)
            .array()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BEACON_SERVICE_UUID))
            .addManufacturerData(0xFFFF, manufacturerData)
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(LOG_TAG, "Beaconing BLE avviato (SOS=$isSosActive)")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(LOG_TAG, "Errore avvio Beaconing: $errorCode")
            }
        })

        startBleScanner()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "BLUETOOTH_SCAN non concesso, BLE scanner disabilitato")
            return
        }
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
        
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BEACON_SERVICE_UUID))
            .build()
            
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
            
        scanner.startScan(listOf<ScanFilter>(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(0xFFFF) ?: return
                if (data.size < 5) return
                
                val buffer = ByteBuffer.wrap(data)
                val remoteIdHash = buffer.int
                val remoteStatus = buffer.get().toInt()
                
                var remoteLat = 0.0f
                var remoteLon = 0.0f
                if (data.size >= 13) {
                    remoteLat = buffer.float
                    remoteLon = buffer.float
                }
                
                val key = remoteIdHash.toString()
                val now = System.currentTimeMillis()
                
                if (remoteStatus == 1) {
                    val lastSeen = seenBeaconIds[key] ?: 0L
                    if (now - lastSeen > 5 * 60_000L) { // dedup 5 minuti
                        seenBeaconIds[key] = now
                        val distMsg = if (remoteLat != 0.0f) {
                            val results = FloatArray(1)
                            currentLastLocation?.let { loc ->
                                Location.distanceBetween(loc.latitude, loc.longitude, remoteLat.toDouble(), remoteLon.toDouble(), results)
                                " a circa %.0fm".format(results[0])
                            } ?: ""
                        } else ""

                        // Non loggare se siamo già connessi a questo peer via Nearby
                        // (il beacon BLE è un fallback per quando non c'è connessione dati)
                        val alreadyConnected = connectedEndpoints.isNotEmpty()
                        if (!alreadyConnected) {
                            TrekMeshBus.emitLog("🚨 SOS passivo captato$distMsg! (beacon BLE, nessuna connessione mesh attiva)")
                            if (UserRolePrefs.getRole(this@TrekMeshService) == UserRole.RIFUGIO) {
                                TrekMeshBus.emitLog("Rifugio: avvicinarsi all'escursionista per stabilire connessione mesh e inoltro SOS.")
                            }
                        }
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun updateBeaconStatus(sos: Boolean) {
        isSosActive = sos
        bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        startBleBeaconing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        updateForegroundNotification()
        if (MeshServicePrefs.isEnabled(this)) {
            startAdaptiveScanning()
        } else {
            stopNearby()
            TrekMeshBus.emitLog("Mesh disattivata — monitoraggio passivo SOS BLE attivo")
        }
        return START_STICKY
    }

    private fun updateForegroundNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopNearby() {
        adaptiveScanJob?.cancel()
        adaptiveScanJob = null
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "stopNearby: ${e.message}")
        }
        connectedEndpoints.clear()
        TrekMeshBus.updatePeerCount(0)
    }

    private fun listenForOutgoingMessages() {
        serviceScope.launch {
            TrekMeshBus.outgoing.collect { outgoing ->
                val lat = currentLastLocation?.latitude ?: 0.0
                val lon = currentLastLocation?.longitude ?: 0.0
                val alt = currentLastLocation?.altitude ?: 0.0
                
                // TTL dinamico: i BROADCAST viaggiano più lontano (15 salti)
                val ttl = if (outgoing.type == TYPE_BROADCAST) 15 else INITIAL_TTL
                
                // Se è un SOS, carichiamo i breadcrumbs dal DB
                val crumbs = if (outgoing.type == "SOS") {
                    db.breadcrumbDao().getLast(5).joinToString("|") { "${it.lat},${it.lon}" }
                } else ""

                val msg = MeshMessage(
                    id = outgoing.id,
                    sender = localEndpointName,
                    ttl = ttl,
                    type = outgoing.type,
                    priority = outgoing.priority,
                    text = outgoing.text,
                    description = outgoing.description,
                    imagePath = outgoing.imagePath,
                    lat = lat,
                    lon = lon,
                    alt = alt,
                    breadcrumbs = crumbs
                )
                seenMessageIds.add(msg.id)
                ownMessageIds.add(msg.id)
                persistMessage(msg, "PENDING")
                TrekMeshBus.emitMessage(msg.id, localEndpointName, msg.type, msg.priority,
                    msg.text, msg.description, msg.imagePath, lat, lon, alt, isOwn = true, ttl = ttl)

                if (msg.type == "SOS" && msg.priority >= 3) {
                    forceHighPowerScan()
                    updateBeaconStatus(true)
                    if (UserRolePrefs.getRole(this@TrekMeshService) == UserRole.RIFUGIO) {
                        serviceScope.launch {
                            val relayed = ProtezioneCivileRelay.sendAlert(
                                messageId = msg.id,
                                sender = localEndpointName,
                                type = msg.type,
                                priority = msg.priority,
                                text = msg.text,
                                description = msg.description,
                                rifugioName = localEndpointName,
                                dao = db.pendingAlertDao()
                            )
                            if (relayed) {
                                TrekMeshBus.emitLog("SOS inoltrato alla Protezione Civile ✓")
                                showRelayConfirmNotification(localEndpointName)
                            }
                        }
                    }
                }

                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("Nessun dispositivo connesso — messaggio bufferizzato")
                    return@collect
                }
                sendToAll(msg)
            }
        }
    }

    private fun sendToAll(msg: MeshMessage) {
        val (filePayload, filePayloadId) = buildFilePayload(msg.imagePath)
        val wire = msg.toWireFormat(filePayloadId)
        val bytesPayload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
        
        connectedEndpoints.forEach { endpointId ->
            // Invia immediatamente
            connectionsClient.sendPayload(endpointId, bytesPayload)
            filePayload?.let { connectionsClient.sendPayload(endpointId, it) }
        }
        
        // Se è un messaggio a bassa priorità (BROADCAST) e abbiamo molti endpoint, 
        // potremmo batchare, ma per ora la priorità è gestita dal DAO nel flush.
    }

    private fun forwardToAllExcept(msg: MeshMessage, excludeEndpointId: String) {
        val targets = connectedEndpoints - excludeEndpointId
        if (targets.isEmpty()) return
        
        val (filePayload, filePayloadId) = buildFilePayload(msg.imagePath)
        val wire = msg.toWireFormat(filePayloadId)
        val bytesPayload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
        
        targets.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, bytesPayload)
            filePayload?.let { connectionsClient.sendPayload(endpointId, it) }
        }
    }

    private suspend fun flushBufferTo(endpointId: String) {
        val buffered = db.messageDao().getAll() // Ora ordinati con SOS > INFO > BROADCAST
        if (buffered.isEmpty()) return
        
        val name = endpointNames[endpointId] ?: endpointId
        TrekMeshBus.emitLog("Sincronizzazione mesh con $name: invio ${buffered.size} messaggi (priorità SOS prima)...")
        
        // Separiamo i tipi per sicurezza ulteriore nell'invio sequenziale
        val sosMessages = buffered.filter { it.type == "SOS" }
        val otherMessages = buffered.filter { it.type != "SOS" }

        // Invia prima tutti gli SOS
        sosMessages.forEach { entity ->
            if (entity.ttl <= 0) return@forEach
            sendEntityToEndpoint(entity, endpointId)
        }
        
        // Poi il resto (INFO e BROADCAST)
        otherMessages.forEach { entity ->
            if (entity.ttl <= 0) return@forEach
            sendEntityToEndpoint(entity, endpointId)
        }
        
        db.messageDao().deleteExpired()
    }

    private fun initialTtlFor(type: String) = if (type == TYPE_BROADCAST) 15 else INITIAL_TTL

    private fun sendEntityToEndpoint(entity: MessageEntity, endpointId: String) {
        val (filePayload, filePayloadId) = buildFilePayload(entity.imagePath)
        val msg = MeshMessage(entity.id, entity.sender, entity.ttl, entity.type,
            entity.priority, entity.text, entity.description, entity.imagePath, entity.lat, entity.lon, entity.alt)
        val wire = msg.toWireFormat(filePayloadId)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(wire.toByteArray(Charsets.UTF_8)))
        filePayload?.let { connectionsClient.sendPayload(endpointId, it) }
    }

    private fun buildFilePayload(imagePath: String?): Pair<Payload?, Long> {
        val file = imagePath?.let { File(it) }?.takeIf { it.exists() } ?: return Pair(null, 0L)
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val payload = Payload.fromFile(pfd)
            Pair(payload, payload.id)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Impossibile creare FILE payload per $imagePath", e)
            Pair(null, 0L)
        }
    }

    private suspend fun persistMessage(msg: MeshMessage, status: String) {
        db.messageDao().insert(msg.toEntity(status))
        db.messageDao().pruneOldest(MAX_BUFFER_SIZE)
    }

    private fun startPeriodicCleanup() {
        serviceScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                db.messageDao().deleteExpired()
                db.messageDao().pruneOldest(MAX_BUFFER_SIZE)
                Log.d(LOG_TAG, "Cleanup periodico eseguito")
            }
        }
    }

    // --- Encounter tracking ---

    /** A peer is a rifugio if its name does NOT follow the "Hiker-XXXX" pattern. */
    private fun isRifugioPeer(name: String) = !name.startsWith("Hiker-")

    private suspend fun sendEncountersToRifugio(endpointId: String) {
        if (!EncounterLogger.hasEncounters(this)) {
            TrekMeshBus.emitLog("📋 Nessun incontro da inviare al rifugio")
            return
        }
        val data = EncounterLogger.popEncounters(this) ?: return
        val csvText = String(data, Charsets.UTF_8)
        Log.d(LOG_TAG, "Invio encounters al rifugio ($endpointId):\n$csvText")
        val payload = "$TYPE_ENCOUNTERS|$localEndpointName|$csvText"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload.toByteArray(Charsets.UTF_8)))
        TrekMeshBus.emitLog("📋 ${csvText.trim().lines().size} incontri inviati al rifugio")
    }

    private fun handleIncomingEncounters(endpointId: String, raw: String) {
        val role = UserRolePrefs.getRole(this) ?: UserRole.HIKER
        Log.d(LOG_TAG, "handleIncomingEncounters: role=$role raw_prefix=${raw.take(60)}")
        if (role != UserRole.RIFUGIO) {
            Log.w(LOG_TAG, "Scartato ENCOUNTERS: questo nodo non è un rifugio")
            return
        }
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) {
            Log.w(LOG_TAG, "ENCOUNTERS malformato: ${parts.size} parti")
            return
        }
        val sender = parts[1]
        val csvData = parts[2].toByteArray(Charsets.UTF_8)
        Log.d(LOG_TAG, "Registro incontri da $sender: ${parts[2].take(100)}")
        EncounterLogger.mergeIntoRegistry(this, csvData, sender)
        TrekMeshBus.emitLog("📋 Registro aggiornato con gli incontri di $sender")
    }

    private fun startAdaptiveScanning() {
        adaptiveScanJob?.cancel()
        adaptiveScanJob = serviceScope.launch {
            while (isActive) {
                if (connectedEndpoints.isNotEmpty()) {
                    if (currentScanMode != ScanMode.LOW_POWER) {
                        currentScanMode = ScanMode.LOW_POWER
                        startNetworking(highPower = false)
                    }
                    delay(30000)
                    continue
                }

                currentScanMode = ScanMode.LOW_POWER
                startNetworking(highPower = false)
                delay(SCAN_LOW_POWER_MS)

                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("Nessun dispositivo trovato, attivo WiFi Direct per scansione profonda...")
                    currentScanMode = ScanMode.HIGH_POWER
                    startNetworking(highPower = true)
                    delay(SCAN_HIGH_POWER_MS)
                }
            }
        }
    }

    private fun forceHighPowerScan() {
        TrekMeshBus.emitLog("Priorità SOS: Attivazione WiFi Direct per massimizzare raggio...")
        adaptiveScanJob?.cancel()
        currentScanMode = ScanMode.HIGH_POWER
        startNetworking(highPower = true)
        serviceScope.launch {
            delay(SCAN_HIGH_POWER_MS * 2) 
            startAdaptiveScanning()
        }
    }

    private fun startNetworking(highPower: Boolean) {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        val strategy = Strategy.P2P_CLUSTER
        
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(!highPower)
            .build()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .setLowPower(!highPower)
            .build()

        connectionsClient.startAdvertising(localEndpointName, serviceId,
            connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener { 
                val mode = if (highPower) "ALTA POTENZA (WiFi)" else "BASSO CONSUMO (BT)"
                TrekMeshBus.emitLog("[$localEndpointName] In ascolto modalità $mode...")
            }
            .addOnFailureListener { e -> TrekMeshBus.emitLog("Errore advertising: ${e.message}") }

        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { TrekMeshBus.emitLog("Scansione avviata...") }
            .addOnFailureListener { e -> TrekMeshBus.emitLog("Errore discovery: ${e.message}") }
    }

    private fun retryConnection(endpointId: String) {
        connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
    }

    private fun generateEndpointName(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02X".format(it) }
        return when (UserRolePrefs.getRole(this)) {
            UserRole.RIFUGIO -> {
                val prefs = getSharedPreferences("trekmesh_node", MODE_PRIVATE)
                val stored = prefs.getString("rifugio_name", null)
                if (stored != null) stored
                else {
                    val name = "Rifugio-$suffix"
                    prefs.edit().putString("rifugio_name", name).apply()
                    name
                }
            }
            else -> {
                val prefs = getSharedPreferences("trekmesh_node", MODE_PRIVATE)
                prefs.getString("hiker_name", null) ?: run {
                    val name = "Hiker-$suffix"
                    prefs.edit().putString("hiker_name", name).apply()
                    name
                }
            }
        }
    }

    private fun showMessageNotification(
        msgId: String,
        sender: String,
        type: String,
        priority: Int,
        text: String,
        description: String = "",
        ttl: Int = 0,
        timestamp: Long = System.currentTimeMillis(),
        lat: Double = 0.0,
        lon: Double = 0.0,
        alt: Double = 0.0
    ) {
        val isSos = type == "SOS"
        val filter = NotificationPrefs.getFilter(this)
        val shouldShow = when (filter) {
            NotificationFilter.ALL       -> true
            NotificationFilter.SOS_ONLY  -> isSos
            NotificationFilter.INFO_ONLY -> !isSos
            NotificationFilter.DISABLED  -> false
        }
        if (!shouldShow) return

        val detailIntent = Intent(this, MessageDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MessageDetailActivity.EXTRA_ID,        msgId)
            putExtra(MessageDetailActivity.EXTRA_SENDER,    sender)
            putExtra(MessageDetailActivity.EXTRA_TYPE,      type)
            putExtra(MessageDetailActivity.EXTRA_PRIORITY,  priority)
            putExtra(MessageDetailActivity.EXTRA_TEXT,      text)
            putExtra(MessageDetailActivity.EXTRA_DESC,      description)
            putExtra(MessageDetailActivity.EXTRA_STATUS,    "RECEIVED")
            putExtra(MessageDetailActivity.EXTRA_TTL,       ttl)
            putExtra(MessageDetailActivity.EXTRA_TIMESTAMP, timestamp)
            putExtra(MessageDetailActivity.EXTRA_LAT,       lat)
            putExtra(MessageDetailActivity.EXTRA_LON,       lon)
            putExtra(MessageDetailActivity.EXTRA_ALT,       alt)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alertNotificationCounter,
            detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isSos) "🆘 SOS da $sender (priorità $priority)" else "📩 Messaggio da $sender"
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ALERT_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(if (isSos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isSos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(if (isSos) longArrayOf(0, 500, 200, 500, 200, 500) else longArrayOf(0, 250))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(alertNotificationCounter++, notification)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "TrekMesh Protezione",
            NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Notifica di servizio P2P TrekMesh" }
        val alertChannel = NotificationChannel(NOTIFICATION_CHANNEL_ALERT_ID, "TrekMesh Messaggi",
            NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "Notifiche per messaggi e SOS ricevuti"
                enableVibration(true)
            }
        val relayChannel = NotificationChannel(NOTIFICATION_CHANNEL_RELAY_ID, "TrekMesh Relay PC",
            NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Conferma inoltro SOS alla Protezione Civile" }
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
        nm.createNotificationChannel(relayChannel)
    }

    fun showRelayConfirmNotification(sender: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_RELAY_ID)
            .setContentTitle("✅ SOS inoltrato alla Protezione Civile")
            .setContentText("L'SOS di $sender è stato trasmesso con successo.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ALERT_BASE_ID - 1, notification)
    }

    private fun createNotification(): Notification {
        val meshEnabled = MeshServicePrefs.isEnabled(this)
        val text = if (meshEnabled)
            "Mesh attiva — rete P2P in ascolto"
        else
            "Monitoraggio passivo SOS attivo (mesh disattivata)"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TrekMesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        adaptiveScanJob?.cancel()
        serviceScope.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
