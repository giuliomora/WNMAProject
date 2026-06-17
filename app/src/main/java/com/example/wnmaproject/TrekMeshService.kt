package com.example.trekmesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.trekmesh.db.MessageEntity
import com.example.trekmesh.db.TrekMeshDatabase
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
import java.security.SecureRandom
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
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ALERT_BASE_ID = 1000
        private const val INITIAL_TTL = 7
        private const val MAX_BUFFER_SIZE = 200
        private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L
        private const val TYPE_MSG = "MSG"
        private const val TYPE_ACK = "ACK"
        private const val FIELD_SEP = "" // ASCII Unit Separator

        private const val SCAN_LOW_POWER_MS = 3 * 60 * 1000L // 3 minuti solo BT
        private const val SCAN_HIGH_POWER_MS = 45 * 1000L    // 45 secondi BT + WiFi
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
        val imagePath: String? = null
    ) {
        fun toWireFormat(filePayloadId: Long = 0L): String {
            val encrypted = CryptoHelper.encrypt("$text$FIELD_SEP$description")
            return "$TYPE_MSG|$id|$sender|$ttl|$type|$priority|$filePayloadId|$encrypted"
        }

        fun toEntity(status: String = "PENDING") =
            MessageEntity(id, sender, ttl, type, priority, text, description, imagePath, status)
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String
    private lateinit var db: TrekMeshDatabase
    private lateinit var imagesDir: File
    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                ProtezioneCivileRelay.retryPending(db.pendingAlertDao())
            }
        }
    }

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val ownMessageIds = mutableSetOf<String>()

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
                TrekMeshBus.emitLog("Connesso a $name")
                serviceScope.launch { flushBufferTo(endpointId) }
            } else {
                endpointNames.remove(endpointId)
                TrekMeshBus.emitLog("Connessione fallita (codice: ${resolution.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
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
            val name = endpointNames[endpointId] ?: endpointId
            TrekMeshBus.emitLog("Dispositivo perso: $name")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = String(payload.asBytes() ?: return, Charsets.UTF_8)
                    when (raw.substringBefore("|")) {
                        TYPE_MSG -> handleIncomingMessage(endpointId, raw)
                        TYPE_ACK -> handleIncomingAck(raw)
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
        val sepIdx = decrypted.indexOf(FIELD_SEP)
        val text = if (sepIdx >= 0) decrypted.substring(0, sepIdx) else decrypted
        val description = if (sepIdx >= 0) decrypted.substring(sepIdx + 1) else ""

        if (!seenMessageIds.add(msgId)) {
            Log.d(LOG_TAG, "Messaggio duplicato ignorato: $msgId")
            return
        }

        if (filePayloadId != 0L) pendingFileForMessage[filePayloadId] = msgId

        val receivedTtl = ttl - 1
        TrekMeshBus.emitMessage(msgId, sender, type, priority, text, description, null, isOwn = false, ttl = receivedTtl)
        serviceScope.launch {
            db.messageDao().insert(MessageEntity(msgId, sender, receivedTtl, type, priority, text, description, null, "RECEIVED"))
        }
        sendAck(endpointId, msgId)
        showMessageNotification(sender, type, priority, text)

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
                val logMsg = if (relayed) "SOS inoltrato alla Protezione Civile ✓"
                             else "Connessione assente — SOS in coda, verrà reinoltrato automaticamente"
                TrekMeshBus.emitLog(logMsg)
            }
        }

        if (ttl > 1) {
            val forward = MeshMessage(msgId, sender, ttl - 1, type, priority, text, description)
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
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        serviceScope.launch {
            seenMessageIds += db.messageDao().getAllIds()
            db.messageDao().getAll().forEach { e ->
                if (e.status != "RECEIVED") ownMessageIds.add(e.id)
                TrekMeshBus.emitMessage(
                    id = e.id,
                    sender = e.sender,
                    type = e.type,
                    priority = e.priority,
                    text = e.text,
                    description = e.description,
                    imagePath = e.imagePath,
                    isOwn = e.status != "RECEIVED",
                    ttl = e.ttl,
                    timestamp = e.timestamp
                )
                if (e.status == "DELIVERED") {
                    TrekMeshBus.updateMessageStatus(e.id, "DELIVERED")
                }
            }
        }
        listenForOutgoingMessages()
        startPeriodicCleanup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startAdaptiveScanning()
        return START_STICKY
    }

    private fun listenForOutgoingMessages() {
        serviceScope.launch {
            TrekMeshBus.outgoing.collect { outgoing ->
                val msg = MeshMessage(
                    id = outgoing.id,
                    sender = localEndpointName,
                    ttl = INITIAL_TTL,
                    type = outgoing.type,
                    priority = outgoing.priority,
                    text = outgoing.text,
                    description = outgoing.description,
                    imagePath = outgoing.imagePath
                )
                seenMessageIds.add(msg.id)
                ownMessageIds.add(msg.id)
                persistMessage(msg, "PENDING")
                TrekMeshBus.emitMessage(msg.id, localEndpointName, msg.type, msg.priority,
                    msg.text, msg.description, msg.imagePath, isOwn = true)

                if (msg.type == "SOS" && msg.priority >= 3) {
                    forceHighPowerScan()
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
                        val logMsg = if (relayed) "SOS inoltrato alla Protezione Civile ✓"
                                     else "Connessione assente — SOS in coda, verrà reinoltrato automaticamente"
                        TrekMeshBus.emitLog(logMsg)
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
        val buffered = db.messageDao().getAll() // already sorted by effective priority DESC
        if (buffered.isEmpty()) return
        TrekMeshBus.emitLog("Invio ${buffered.size} messaggi bufferizzati a ${endpointNames[endpointId] ?: endpointId}...")
        buffered.forEach { entity ->
            if (entity.ttl <= 0) return@forEach
            val (filePayload, filePayloadId) = buildFilePayload(entity.imagePath)
            val msg = MeshMessage(entity.id, entity.sender, entity.ttl, entity.type,
                entity.priority, entity.text, entity.description, entity.imagePath)
            val wire = msg.toWireFormat(filePayloadId)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(wire.toByteArray(Charsets.UTF_8)))
            filePayload?.let { connectionsClient.sendPayload(endpointId, it) }
        }
        db.messageDao().deleteExpired()
    }

    // Returns (filePayload, filePayloadId) — both null/0 if no image or file missing
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

    private fun startAdaptiveScanning() {
        adaptiveScanJob?.cancel()
        adaptiveScanJob = serviceScope.launch {
            while (isActive) {
                // Se siamo già connessi a qualcuno, restiamo in Low Power per risparmiare
                if (connectedEndpoints.isNotEmpty()) {
                    if (currentScanMode != ScanMode.LOW_POWER) {
                        currentScanMode = ScanMode.LOW_POWER
                        startNetworking(highPower = false)
                    }
                    delay(30000) // Ricontrolla ogni 30s
                    continue
                }

                // Modalità Low Power (Solo Bluetooth)
                currentScanMode = ScanMode.LOW_POWER
                startNetworking(highPower = false)
                delay(SCAN_LOW_POWER_MS)

                // Se ancora nessuno è connesso, passiamo a High Power (WiFi Direct)
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
        // Riprendi il ciclo adattivo dopo un po'
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
            .setLowPower(!highPower) // Se non è high power, usa modalità a basso consumo
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

    private fun generateEndpointName(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02X".format(it) }
        val prefix = when (UserRolePrefs.getRole(this)) {
            UserRole.RIFUGIO -> "Rifugio"
            else -> "Hiker"
        }
        return "$prefix-$suffix"
    }

    private fun showMessageNotification(sender: String, type: String, priority: Int, text: String) {
        val isSos = type == "SOS"
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
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TrekMesh")
            .setContentText("TrekMesh sta proteggendo la tua escursione")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        adaptiveScanJob?.cancel()
        serviceScope.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
