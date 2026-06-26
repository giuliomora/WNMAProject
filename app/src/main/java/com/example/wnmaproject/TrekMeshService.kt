package com.example.trekmesh

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.content.Context
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
import android.os.PowerManager
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
        private const val TYPE_BENCH_PING    = "BENCH_PING"
        private const val TYPE_BENCH_PONG    = "BENCH_PONG"
        private const val TYPE_BENCH_THRU    = "BENCH_THRU"
        private const val TYPE_BENCH_THRU_PONG = "BENCH_THRU_PONG"
        private const val FIELD_SEP = "" // ASCII Unit Separator

        private const val SCAN_LOW_POWER_MS = 3 * 60 * 1000L // 3 minuti solo BT
        private const val SCAN_HIGH_POWER_MS = 45 * 1000L    // 45 secondi BT + WiFi
        
        private const val BREADCRUMB_INTERVAL_MS = 15 * 60 * 1000L // 15 minuti
        private const val WEATHER_RELAY_INTERVAL_MS = 60 * 60 * 1000L // 1 ora

        const val ACTION_AUTO_SOS = "com.example.trekmesh.ACTION_AUTO_SOS"

        private val BEACON_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb") // TrekMesh Heartbeat
    }

    private enum class ScanMode { LOW_POWER, HIGH_POWER }
    private var currentScanMode = ScanMode.LOW_POWER
    private var adaptiveScanJob: kotlinx.coroutines.Job? = null
    private var benchSeriesActive = false

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

    private data class BenchThruState(val total: Int, val firstAt: Long, var received: Int = 0, var totalBytes: Int = 0)

    private lateinit var connectionsClient: ConnectionsClient
    private val serviceId = "com.example.trekmesh"
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

    // Benchmark: reconnection timing (name -> disconnectedAt ms)
    private val disconnectedAt = mutableMapOf<String, Long>()
    // Benchmark: incoming BENCH_PING counters (testId -> received count)
    private val benchPingCounts = mutableMapOf<String, Int>()
    // Benchmark: ACK round-trip tracking (msgId -> sentAt ms)
    private val msgSentAt = mutableMapOf<String, Long>()
    // Benchmark: throughput sender (testId -> sentAt, totalBytes)
    private val benchThroughputSentAt = mutableMapOf<String, Pair<Long, Int>>()
    // Benchmark: throughput receiver (testId -> receivedCount, total, firstReceivedAt, totalBytes)
    private val benchThroughputRecv = mutableMapOf<String, BenchThruState>()
    // Benchmark: RSSI map from BLE scanner (deviceHash -> rssi, timestamp)
    private val lastRssiByHash = mutableMapOf<String, Pair<Int, Long>>()
    // Benchmark: timestamp of last startNetworking() call, used to compute discovery time
    private var scanStartedAt = 0L

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
            TrekMeshBus.emitLog("Incoming connection from ${connectionInfo.endpointName}, accepting...")
            BenchmarkLogger.start("conn_handshake:${connectionInfo.endpointName}")
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
                TrekMeshBus.emitLog("Connected to $name")
                val connLatencyMs = BenchmarkLogger.stopAndGet("conn_handshake:$name")
                if (connLatencyMs != null)
                    BenchmarkLogger.log("CONN_HANDSHAKE peer=$name ms=$connLatencyMs (onConnectionInitiated→onConnectionResult)")
                disconnectedAt.remove(name)?.let { lostAt ->
                    val reconnectMs = System.currentTimeMillis() - lostAt
                    BenchmarkLogger.log("RECONNECT_OK name=$name elapsed=${reconnectMs}ms")
                }
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
                    TrekMeshBus.emitLog("Connection failed (code: $code)")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            TrekMeshBus.updatePeerCount(connectedEndpoints.size)
            TrekMeshBus.emitLog("Disconnected from $name")
            disconnectedAt[name] = System.currentTimeMillis()
            BenchmarkLogger.log("RECONNECT_LOST name=$name ts=${System.currentTimeMillis()}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
        ) {
            if (endpointId in connectedEndpoints || endpointId in pendingEndpoints) return
            if (endpointNames.values.contains(info.endpointName)) return
            val mode = if (currentScanMode == ScanMode.HIGH_POWER) "HIGH_POWER" else "LOW_POWER"
            val foundAt = System.currentTimeMillis()
            val discoveryMs = if (scanStartedAt > 0) foundAt - scanStartedAt else -1L
            BenchmarkLogger.log("ENDPOINT_FOUND name=${info.endpointName} mode=$mode discoveryTime=${discoveryMs}ms ts=$foundAt")
            TrekMeshBus.emitLog("Device found: ${info.endpointName}, requesting connection...")
            pendingEndpoints.add(endpointId)
            endpointNames[endpointId] = info.endpointName
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            // onEndpointLost riguarda il discovery, non la connessione:
            // se il peer è già connesso, la connessione rimane attiva
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
                        TYPE_BENCH_PING      -> handleBenchPing(endpointId, raw)
                        TYPE_BENCH_PONG      -> handleBenchPong(raw)
                        TYPE_BENCH_THRU      -> handleBenchThru(endpointId, raw)
                        TYPE_BENCH_THRU_PONG -> handleBenchThruPong(raw)
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
                TrekMeshBus.emitLog("Received historical track (Breadcrumbs) from $sender")
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
                    TrekMeshBus.emitLog("SOS forwarded to \"Protezione Civile\" ✓")
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
        msgSentAt.remove(originalMsgId)?.let { sentAt ->
            BenchmarkLogger.log("ACK_RTT msgId=${originalMsgId.take(8)} rtt=${System.currentTimeMillis() - sentAt}ms peer=$ackerName")
        }
        serviceScope.launch { db.messageDao().updateStatus(originalMsgId, "DELIVERED") }
        TrekMeshBus.updateMessageStatus(originalMsgId, "DELIVERED")
        TrekMeshBus.emitLog("Message delivered to $ackerName")
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
            "ACKNOWLEDGED" -> "acknowledged by $rifugioName"
            "RESOLVED"     -> "resolved by $rifugioName"
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
        BenchmarkLogger.init(this)
        listenForBenchPingTrigger()
        listenForBenchControl()
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
                        description = "Automatic weather update from Rifugio"
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
                    SafetyTimerAction.START_3M  -> startSafetyTimer(3 * 60)
                    SafetyTimerAction.START_30M -> startSafetyTimer(30 * 60)
                    SafetyTimerAction.START_1H  -> startSafetyTimer(60 * 60)
                    SafetyTimerAction.START_2H  -> startSafetyTimer(120 * 60)
                    SafetyTimerAction.STOP      -> stopSafetyTimer()
                }
            }
        }
    }

    private fun startSafetyTimer(seconds: Int) {
        val endTime = System.currentTimeMillis() + seconds * 1000L
        scheduleSafetyAlarm(endTime)

        safetyTimerJob?.cancel()
        safetyTimerJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remaining = ((endTime - now) / 1000).toInt()
                if (remaining <= 0) break

                TrekMeshBus.updateSafetyTimer(remaining)
                delay(1000)
            }
            TrekMeshBus.updateSafetyTimer(0)
            // If the service is still running when the in-process timer reaches 0,
            // trigger the safety alarm immediately instead of waiting for the
            // AlarmManager broadcast. This reduces delays on devices where exact
            // alarms may be deferred.
            try {
                TrekMeshBus.emitLog("Safety timer expired (in-service) — triggering SOS immediately")
                // Cancel scheduled alarm (if any) to avoid duplicate triggers
                cancelSafetyAlarm()
                triggerSafetyAlarm()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Error while triggering in-service safety alarm: ${e.message}")
            }
        }
        TrekMeshBus.emitLog("Safety timer started: ${seconds / 60} minutes")
    }

    private fun stopSafetyTimer() {
        cancelSafetyAlarm()
        safetyTimerJob?.cancel()
        TrekMeshBus.updateSafetyTimer(0)
        TrekMeshBus.emitLog("Safety timer stopped ✓")
    }

    private fun scheduleSafetyAlarm(endTimeMillis: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(this, SafetyTimerReceiver::class.java).apply {
            action = SafetyTimerReceiver.ACTION_SAFETY_TIMER_EXPIRED
        }
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pi)
        }
    }

    private fun cancelSafetyAlarm() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(this, SafetyTimerReceiver::class.java).apply {
            action = SafetyTimerReceiver.ACTION_SAFETY_TIMER_EXPIRED
        }
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun triggerSafetyAlarm() {
        // Ensure any scheduled alarm is cancelled to avoid duplicate triggers
        cancelSafetyAlarm()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrekMesh:AutoSosWakeLock")
        wakeLock.acquire(10000L) // Tieni sveglio per 10 secondi per trasmettere

        TrekMeshBus.emitLog("⚠️ ALERT: Missed check-in! Sending automatic SOS...")
        
        // Forza scansione ad alta potenza per aumentare le chance di invio
        forceHighPowerScan()
        updateBeaconStatus(true)

        TrekMeshBus.sendMessage(OutgoingMessage(
            type = "SOS",
            priority = 3,
            text = "AUTOMATIC SOS: Missed check-in"
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
                lastRssiByHash[key] = Pair(result.rssi, now)
                
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
                            TrekMeshBus.emitLog("🚨 Passive SOS detected$distMsg! (BLE beacon, no active mesh connection)")
                            if (UserRolePrefs.getRole(this@TrekMeshService) == UserRole.RIFUGIO) {
                                TrekMeshBus.emitLog("Rifugio: approach the hiker to establish mesh connection and SOS relay.")
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

        if (intent?.action == ACTION_AUTO_SOS) {
            triggerSafetyAlarm()
        }

        if (MeshServicePrefs.isEnabled(this)) {
            startAdaptiveScanning()
        } else {
            stopNearby()
            TrekMeshBus.emitLog("Mesh disabled — passive BLE SOS monitoring active")
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
                    // Show a local notification immediately for the SOS so the user
                    // sees it even if the app is backgrounded / screen is off.
                    try {
                        showMessageNotification(
                            msgId = msg.id,
                            sender = localEndpointName,
                            type = msg.type,
                            priority = msg.priority,
                            text = msg.text,
                            description = msg.description,
                            ttl = ttl,
                            timestamp = System.currentTimeMillis(),
                            lat = lat,
                            lon = lon,
                            alt = alt
                        )
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Unable to show SOS notification: ${e.message}")
                    }
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
                                TrekMeshBus.emitLog("SOS forwarded to \"Protezione Civile\" ✓")
                                showRelayConfirmNotification(localEndpointName)
                            }
                        }
                    }
                }

                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("No devices connected — message buffered")
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
        msgSentAt[msg.id] = System.currentTimeMillis()
        BenchmarkLogger.log("MSG_SENT id=${msg.id.take(8)} type=${msg.type} peers=${connectedEndpoints.size} ts=${System.currentTimeMillis()}")

        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, bytesPayload)
            filePayload?.let { connectionsClient.sendPayload(endpointId, it) }
        }
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
        TrekMeshBus.emitLog("Mesh sync with $name: sending ${buffered.size} messages (SOS priority first)...")
        
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
                val before = db.messageDao().count()
                db.messageDao().deleteExpired()
                db.messageDao().pruneOldest(MAX_BUFFER_SIZE)
                val after = db.messageDao().count()
                val removed = before - after
                Log.d(LOG_TAG, "Cleanup: removed $removed messages, remaining $after")
                BenchmarkLogger.log("CLEANUP: before=$before removed=$removed remaining=$after ts=${System.currentTimeMillis()}")
            }
        }
    }

    // --- Encounter tracking ---

    /** A peer is a rifugio if its name does NOT follow the "Hiker-XXXX" pattern. */
    private fun isRifugioPeer(name: String) = !name.startsWith("Hiker-")

    private suspend fun sendEncountersToRifugio(endpointId: String) {
        if (!EncounterLogger.hasEncounters(this)) {
            TrekMeshBus.emitLog("📋 No encounters to send to rifugio")
            return
        }
        val data = EncounterLogger.popEncounters(this) ?: return
        val csvText = String(data, Charsets.UTF_8)
        Log.d(LOG_TAG, "Invio encounters al rifugio ($endpointId):\n$csvText")
        val payload = "$TYPE_ENCOUNTERS|$localEndpointName|$csvText"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload.toByteArray(Charsets.UTF_8)))
        TrekMeshBus.emitLog("📋 ${csvText.trim().lines().size} encounters sent to rifugio")
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
        TrekMeshBus.emitLog("📋 Registry updated with encounters from $sender")
    }

    // ── Benchmark functions ────────────────────────────────────────────────────

    private fun handleBenchPing(endpointId: String, raw: String) {
        // BENCH_PING|testId|seq|total
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) return
        val testId = parts[1]
        val seq = parts[2].toIntOrNull() ?: return
        val total = parts[3].toIntOrNull() ?: return
        val newCount = (benchPingCounts[testId] ?: 0) + 1
        benchPingCounts[testId] = newCount
        Log.d(LOG_TAG, "BENCH_PING $seq/$total (received $newCount)")
        // Send PONG when last ping arrives or all expected received
        if (seq == total - 1 || newCount == total) {
            val pong = "$TYPE_BENCH_PONG|$testId|$newCount|$total"
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(pong.toByteArray(Charsets.UTF_8)))
            benchPingCounts.remove(testId)
        }
    }

    private fun handleBenchPong(raw: String) {
        // BENCH_PONG|testId|received|total
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) return
        val testId = parts[1]
        val received = parts[2].toIntOrNull() ?: return
        val total = parts[3].toIntOrNull() ?: return
        val lost = total - received
        val pct = if (total > 0) received * 100 / total else 0
        BenchmarkLogger.log("PACKET_LOSS RESULT testId=${testId.take(8)} received=$received/$total ($pct%) lost=$lost")
    }

    private fun listenForBenchPingTrigger() {
        serviceScope.launch {
            TrekMeshBus.benchPingTrigger.collect { total ->
                if (connectedEndpoints.isEmpty()) {
                    BenchmarkLogger.log("PACKET_LOSS ABORTED: no connected peers")
                    return@collect
                }
                val testId = java.util.UUID.randomUUID().toString().take(8)
                BenchmarkLogger.log("PACKET_LOSS START testId=$testId total=$total peers=${connectedEndpoints.size}")
                BenchmarkLogger.logBattery(this@TrekMeshService)
                val startMs = System.currentTimeMillis()
                repeat(total) { seq ->
                    val wire = "$TYPE_BENCH_PING|$testId|$seq|$total"
                    val payload = Payload.fromBytes(wire.toByteArray(Charsets.UTF_8))
                    connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
                    delay(20) // 50 msg/sec max send rate
                }
                val sendMs = System.currentTimeMillis() - startMs
                BenchmarkLogger.log("PACKET_LOSS SENT $total pings in ${sendMs}ms (waiting for PONG...)")
            }
        }
    }

    private fun listenForBenchControl() {
        serviceScope.launch {
            TrekMeshBus.benchControl.collect { ctrl ->
                when (ctrl) {
                    TrekMeshBus.BenchControl.REDISCOVERY              -> runRediscoveryTest()
                    TrekMeshBus.BenchControl.REDISCOVERY_SERIES_LOW  -> runRediscoverySeriesTest(highPower = false)
                    TrekMeshBus.BenchControl.REDISCOVERY_SERIES_HIGH -> runRediscoverySeriesTest(highPower = true)
                    TrekMeshBus.BenchControl.RECOVERY_SERIES         -> runRecoverySeriesTest(10_000L)
                    TrekMeshBus.BenchControl.THROUGHPUT_SERIES_100K  -> runThroughputSeriesTest(100)
                    TrekMeshBus.BenchControl.THROUGHPUT_SERIES_500K  -> runThroughputSeriesTest(500)
                    TrekMeshBus.BenchControl.PACKET_LOSS_SERIES      -> runPacketLossSeriesTest()
                    TrekMeshBus.BenchControl.STRESS_SERIES           -> runStressSeriesTest()
                    TrekMeshBus.BenchControl.RSSI_SNAPSHOT           -> logRssiSnapshot()
                }
            }
        }
    }

    // ── Throughput: sends N×1KB pings, receiver echoes BENCH_THRU_PONG with RTT ──

    private suspend fun runThroughputTest(sizeKb: Int) {
        if (connectedEndpoints.isEmpty()) {
            BenchmarkLogger.log("THROUGHPUT ABORTED: no connected peers")
            return
        }
        val testId = java.util.UUID.randomUUID().toString().take(8)
        val padding = "X".repeat(1000) // ~1 KB per ping
        val pings = sizeKb
        BenchmarkLogger.log("THROUGHPUT_TEST START testId=$testId size=${sizeKb}KB pings=$pings peers=${connectedEndpoints.size}")
        val sentAt = System.currentTimeMillis()
        benchThroughputSentAt[testId] = Pair(sentAt, pings * padding.length)
        for (seq in 0 until pings) {
            val wire = "$TYPE_BENCH_THRU|$testId|$seq|$pings|$padding"
            val payload = Payload.fromBytes(wire.toByteArray())
            connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
            delay(20)
        }
        BenchmarkLogger.log("THROUGHPUT_TEST all $pings pings sent in ${System.currentTimeMillis() - sentAt}ms — waiting THRU_PONG...")
    }

    private fun handleBenchThru(endpointId: String, raw: String) {
        val parts = raw.split("|", limit = 5)
        if (parts.size < 4) return
        val testId = parts[1]
        val seq    = parts[2].toIntOrNull() ?: return
        val total  = parts[3].toIntOrNull() ?: return
        val state  = benchThroughputRecv.getOrPut(testId) {
            BenchThruState(total = total, firstAt = System.currentTimeMillis())
        }
        state.received++
        state.totalBytes += raw.length
        if (state.received == total) {
            val elapsed = System.currentTimeMillis() - state.firstAt
            val kbps = if (elapsed > 0) state.totalBytes / 1024.0 / elapsed * 1000 else 0.0
            BenchmarkLogger.log("THROUGHPUT_RECV testId=$testId received=${state.totalBytes}B in ${elapsed}ms = ${"%.1f".format(kbps)} KB/s")
            val pong = "$TYPE_BENCH_THRU_PONG|$testId|${state.totalBytes}|$elapsed"
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(pong.toByteArray()))
            benchThroughputRecv.remove(testId)
        }
    }

    private fun handleBenchThruPong(raw: String) {
        val parts = raw.split("|")
        if (parts.size < 4) return
        val testId  = parts[1]
        val bytes   = parts[2].toIntOrNull() ?: return
        val elapsed = parts[3].toLongOrNull() ?: return
        val kbps = if (elapsed > 0) bytes / 1024.0 / elapsed * 1000 else 0.0
        BenchmarkLogger.log("THROUGHPUT_PONG testId=$testId rcv=${bytes}B in ${elapsed}ms = ${"%.1f".format(kbps)} KB/s")
        benchThroughputSentAt.remove(testId)?.let { (sentAt, _) ->
            val rtt = System.currentTimeMillis() - sentAt
            BenchmarkLogger.log("THROUGHPUT_RTT testId=$testId sender-side total=${rtt}ms")
        }
    }

    // ── Stress: rapid-fire 10 messages through normal mesh path ──

    private suspend fun runStressTest(count: Int) {
        if (connectedEndpoints.isEmpty()) {
            BenchmarkLogger.log("STRESS_TEST ABORTED: no connected peers")
            return
        }
        val role = getSharedPreferences("settings", MODE_PRIVATE).getString("role", "HIKER") ?: "HIKER"
        val sender = getSharedPreferences("settings", MODE_PRIVATE).getString("node_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        BenchmarkLogger.log("STRESS_TEST START msgs=$count peers=${connectedEndpoints.size}")
        val startAt = System.currentTimeMillis()
        repeat(count) { i ->
            val msg = MeshMessage(
                id       = java.util.UUID.randomUUID().toString(),
                sender   = sender,
                ttl      = 3,
                type     = "INFO",
                priority = 1,
                text     = "[STRESS ${"${i+1}".padStart(2,'0')}/$count]"
            )
            seenMessageIds.add(msg.id)
            ownMessageIds.add(msg.id)
            msgSentAt[msg.id] = System.currentTimeMillis()
            sendToAll(msg)
            delay(100)
        }
        BenchmarkLogger.log("STRESS_TEST all $count sent in ${System.currentTimeMillis() - startAt}ms — watch ACK_RTT lines for latency")
    }

    // ── RSSI snapshot from BLE scanner ──

    private fun logRssiSnapshot() {
        val now = System.currentTimeMillis()
        val recent = lastRssiByHash.filter { now - it.value.second < 30_000 }
        if (recent.isEmpty()) {
            BenchmarkLogger.log("RSSI_SNAPSHOT: no BLE devices seen in last 30s")
            return
        }
        BenchmarkLogger.log("RSSI_SNAPSHOT ts=$now devices=${recent.size}")
        recent.forEach { (hash, pair) ->
            val age = (now - pair.second) / 1000
            BenchmarkLogger.log("  RSSI device=$hash rssi=${pair.first}dBm age=${age}s")
        }
    }

    private suspend fun runRediscoveryTest(highPower: Boolean = false) {
        val mode = if (highPower) "HIGH_POWER" else "LOW_POWER"
        val peers = connectedEndpoints.size
        BenchmarkLogger.log("REDISCOVERY_TEST START mode=$mode peers=$peers ts=${System.currentTimeMillis()}")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        TrekMeshBus.updatePeerCount(0)
        delay(500)
        startNetworking(highPower = highPower)
        BenchmarkLogger.log("REDISCOVERY_TEST scanning in $mode mode... (watch ENDPOINT_FOUND discoveryTime)")
    }

    private fun batteryPct(): Int {
        val bm = getSystemService(android.os.BatteryManager::class.java) ?: return -1
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun batteryLog(start: Int, end: Int, totalSec: Long) =
        BenchmarkLogger.log("  battery: $start% → $end% (consumed ${start - end}% in ${totalSec}s)")

    private fun pauseAdaptiveScanning() {
        benchSeriesActive = true
        adaptiveScanJob?.cancel()
        adaptiveScanJob = null
        BenchmarkLogger.log("  [adaptive scanning paused for benchmark series]")
    }

    private fun resumeAdaptiveScanning() {
        benchSeriesActive = false
        BenchmarkLogger.log("  [adaptive scanning resumed — restarting networking]")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        TrekMeshBus.updatePeerCount(0)
        startNetworking(highPower = false)
        startAdaptiveScanning()
    }

    private suspend fun runRecoverySeriesTest(blackoutMs: Long, count: Int = 5) {
        val blackoutSec = blackoutMs / 1000
        val battStart   = batteryPct()
        val seriesStart = System.currentTimeMillis()
        val results     = mutableListOf<Long>()

        pauseAdaptiveScanning()
        BenchmarkLogger.log("RECOVERY_SERIES START blackout=${blackoutSec}s ×$count battery=$battStart%")
        repeat(count) { i ->
            BenchmarkLogger.log("RECOVERY_SERIES iter=${i + 1}/$count — disconnecting...")
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
            pendingEndpoints.clear()
            TrekMeshBus.updatePeerCount(0)
            BenchmarkLogger.log("RECOVERY_SERIES BLACKOUT ${blackoutSec}s...")
            delay(blackoutMs)
            val iterStart = System.currentTimeMillis()
            startNetworking(highPower = true)
            val deadline = iterStart + 90_000L
            while (connectedEndpoints.isEmpty() && System.currentTimeMillis() < deadline) delay(250)
            if (connectedEndpoints.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - iterStart
                results.add(elapsed)
                BenchmarkLogger.log("RECOVERY_SERIES iter=${i + 1} OK reconnected in ${elapsed}ms")
            } else {
                BenchmarkLogger.log("RECOVERY_SERIES iter=${i + 1} TIMEOUT")
            }
            delay(2_000)
        }
        val totalSec = (System.currentTimeMillis() - seriesStart) / 1000
        BenchmarkLogger.log("━━━ RECOVERY_SERIES RESULT blackout=${blackoutSec}s ━━━")
        if (results.isNotEmpty())
            BenchmarkLogger.log("  success=${results.size}/$count  avg=${results.average().toLong()}ms  min=${results.min()}ms  max=${results.max()}ms")
        else
            BenchmarkLogger.log("  success=0/$count (all timed out)")
        batteryLog(battStart, batteryPct(), totalSec)
        BenchmarkLogger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        resumeAdaptiveScanning()
    }

    private suspend fun runThroughputSeriesTest(sizeKb: Int, count: Int = 5) {
        if (connectedEndpoints.isEmpty()) { BenchmarkLogger.log("THROUGHPUT_SERIES ABORTED: no peers"); return }
        val battStart   = batteryPct()
        val seriesStart = System.currentTimeMillis()
        val waitPerIter = sizeKb * 20L + 2_000L

        pauseAdaptiveScanning()
        BenchmarkLogger.log("THROUGHPUT_SERIES START size=${sizeKb}KB ×$count battery=$battStart%")
        repeat(count) { i ->
            BenchmarkLogger.log("THROUGHPUT_SERIES iter=${i + 1}/$count")
            runThroughputTest(sizeKb)
            delay(waitPerIter)
        }
        val totalSec = (System.currentTimeMillis() - seriesStart) / 1000
        BenchmarkLogger.log("━━━ THROUGHPUT_SERIES RESULT size=${sizeKb}KB ━━━")
        BenchmarkLogger.log("  (see THROUGHPUT_PONG lines above for per-iter KB/s)")
        batteryLog(battStart, batteryPct(), totalSec)
        BenchmarkLogger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        resumeAdaptiveScanning()
    }

    private suspend fun runPacketLossSeriesTest(count: Int = 5) {
        if (connectedEndpoints.isEmpty()) { BenchmarkLogger.log("PACKET_LOSS_SERIES ABORTED: no peers"); return }
        val battStart   = batteryPct()
        val seriesStart = System.currentTimeMillis()
        val waitPerIter = 50 * 50L + 2_000L

        pauseAdaptiveScanning()
        BenchmarkLogger.log("PACKET_LOSS_SERIES START ×$count (50 pings/iter) battery=$battStart%")
        repeat(count) { i ->
            BenchmarkLogger.log("PACKET_LOSS_SERIES iter=${i + 1}/$count")
            TrekMeshBus.triggerBenchPing(50)
            delay(waitPerIter)
        }
        val totalSec = (System.currentTimeMillis() - seriesStart) / 1000
        BenchmarkLogger.log("━━━ PACKET_LOSS_SERIES RESULT ━━━")
        BenchmarkLogger.log("  (see PACKET_LOSS RESULT lines above for per-iter loss %)")
        batteryLog(battStart, batteryPct(), totalSec)
        BenchmarkLogger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        resumeAdaptiveScanning()
    }

    private suspend fun runStressSeriesTest(count: Int = 5) {
        if (connectedEndpoints.isEmpty()) { BenchmarkLogger.log("STRESS_SERIES ABORTED: no peers"); return }
        val battStart   = batteryPct()
        val seriesStart = System.currentTimeMillis()
        val waitPerIter = 10 * 100L + 2_000L

        pauseAdaptiveScanning()
        BenchmarkLogger.log("STRESS_SERIES START ×$count (10 msgs/iter) battery=$battStart%")
        repeat(count) { i ->
            BenchmarkLogger.log("STRESS_SERIES iter=${i + 1}/$count")
            runStressTest(10)
            delay(waitPerIter)
        }
        val totalSec = (System.currentTimeMillis() - seriesStart) / 1000
        BenchmarkLogger.log("━━━ STRESS_SERIES RESULT ━━━")
        BenchmarkLogger.log("  (see ACK_RTT lines above for per-message latency)")
        batteryLog(battStart, batteryPct(), totalSec)
        BenchmarkLogger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        resumeAdaptiveScanning()
    }

    private suspend fun runRediscoverySeriesTest(highPower: Boolean, count: Int = 10) {
        val mode = if (highPower) "HIGH_POWER" else "LOW_POWER"
        val battStart = batteryPct()
        val seriesStart = System.currentTimeMillis()
        val discoveryTimes = mutableListOf<Long>()

        pauseAdaptiveScanning()
        BenchmarkLogger.log("REDISCOVERY_SERIES START mode=$mode count=$count battery=$battStart%")

        repeat(count) { i ->
            BenchmarkLogger.log("REDISCOVERY_SERIES iter=${i + 1}/$count")
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
            pendingEndpoints.clear()
            TrekMeshBus.updatePeerCount(0)
            delay(3_000) // let GMS settle before next startNetworking()

            val iterStart = System.currentTimeMillis()
            startNetworking(highPower = highPower)

            val deadline = iterStart + 90_000L
            while (connectedEndpoints.isEmpty() && System.currentTimeMillis() < deadline) delay(250)

            if (connectedEndpoints.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - iterStart
                discoveryTimes.add(elapsed)
                BenchmarkLogger.log("REDISCOVERY_SERIES iter=${i + 1} OK elapsed=${elapsed}ms")
            } else {
                BenchmarkLogger.log("REDISCOVERY_SERIES iter=${i + 1} TIMEOUT")
            }

            delay(2_000)
        }

        val totalSec = (System.currentTimeMillis() - seriesStart) / 1000
        BenchmarkLogger.log("━━━ REDISCOVERY_SERIES RESULT mode=$mode ━━━")
        if (discoveryTimes.isNotEmpty())
            BenchmarkLogger.log("  success=${discoveryTimes.size}/$count  avg=${discoveryTimes.average().toLong()}ms  min=${discoveryTimes.min()}ms  max=${discoveryTimes.max()}ms")
        else
            BenchmarkLogger.log("  success=0/$count (all timed out)")
        batteryLog(battStart, batteryPct(), totalSec)
        BenchmarkLogger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        resumeAdaptiveScanning()
    }

    private suspend fun runRecoveryTest(blackoutMs: Long) {
        val peers = connectedEndpoints.size
        val blackoutSec = blackoutMs / 1000
        BenchmarkLogger.log("RECOVERY_TEST START peers=$peers blackout=${blackoutSec}s ts=${System.currentTimeMillis()}")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        pendingEndpoints.clear()
        TrekMeshBus.updatePeerCount(0)
        BenchmarkLogger.log("RECOVERY_TEST BLACKOUT for ${blackoutSec}s...")
        delay(blackoutMs)
        BenchmarkLogger.log("RECOVERY_TEST RECONNECTING ts=${System.currentTimeMillis()} (HIGH_POWER mode)")
        // High power for faster recovery, RECONNECT_OK will log elapsed since RECONNECT_LOST
        startNetworking(highPower = true)
    }

    private fun startAdaptiveScanning() {
        if (benchSeriesActive) return  // don't interfere with benchmark series
        adaptiveScanJob?.cancel()
        adaptiveScanJob = serviceScope.launch {
            while (isActive) {
                if (benchSeriesActive) { delay(5_000); continue } // recheck while paused
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
                    TrekMeshBus.emitLog("No devices found, activating Wi-Fi Direct for deep scan...")
                    currentScanMode = ScanMode.HIGH_POWER
                    startNetworking(highPower = true)
                    delay(SCAN_HIGH_POWER_MS)
                }
            }
        }
    }

    private fun forceHighPowerScan() {
        TrekMeshBus.emitLog("SOS priority: activating Wi-Fi Direct to maximise range...")
        adaptiveScanJob?.cancel()
        currentScanMode = ScanMode.HIGH_POWER
        startNetworking(highPower = true)
        serviceScope.launch {
            delay(SCAN_HIGH_POWER_MS * 2) 
            startAdaptiveScanning()
        }
    }

    private fun startNetworking(highPower: Boolean) {
        val mode = if (highPower) "HIGH_POWER(BT+WiFi)" else "LOW_POWER(BT)"
        scanStartedAt = System.currentTimeMillis()
        BenchmarkLogger.log("SCAN_START mode=$mode ts=$scanStartedAt")
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
                TrekMeshBus.emitLog("[$localEndpointName] Listening in $mode mode...")
            }
            .addOnFailureListener { e -> TrekMeshBus.emitLog("Advertising error: ${e.message}") }

        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { TrekMeshBus.emitLog("Discovery started...") }
            .addOnFailureListener { e -> TrekMeshBus.emitLog("Discovery error: ${e.message}") }
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

        val title = if (isSos) "🆘 SOS from $sender (priority $priority)" else "📩 Message from $sender"
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
            .setContentTitle("✅ SOS forwarded to \"Protezione Civile\"")
            .setContentText("The SOS from $sender has been successfully transmitted.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ALERT_BASE_ID - 1, notification)
    }

    private fun createNotification(): Notification {
        val meshEnabled = MeshServicePrefs.isEnabled(this)
        val text = if (meshEnabled)
            "Mesh active — P2P network listening"
        else
            "Passive SOS monitoring active (mesh disabled)"
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
