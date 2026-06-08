package com.example.trekmesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrekMeshService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "trekmesh_p2p_channel"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "TrekMeshService"
        private const val INITIAL_TTL = 7
        private const val MAX_BUFFER_SIZE = 200
        private const val TYPE_MSG = "MSG"
        private const val TYPE_ACK = "ACK"
    }

    private data class MeshMessage(
        val id: String,
        val sender: String,
        val ttl: Int,
        val text: String
    ) {
        // Wire: MSG|id|sender|ttl|encrypted_text
        fun toWireFormat(): String {
            val encrypted = CryptoHelper.encrypt(text)
            return "$TYPE_MSG|$id|$sender|$ttl|$encrypted"
        }
        fun toEntity(status: String = "PENDING") = MessageEntity(id, sender, ttl, text, status)
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String
    private lateinit var db: TrekMeshDatabase

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val ownMessageIds = mutableSetOf<String>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                Log.i(LOG_TAG, "Connessione OK con $name ($endpointId)")
                serviceScope.launch { flushBufferTo(endpointId) }
            } else {
                endpointNames.remove(endpointId)
                Log.w(LOG_TAG, "Connessione fallita con $endpointId: ${resolution.status.statusCode}")
                TrekMeshBus.emitLog("Connessione fallita con $endpointId (codice: ${resolution.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            Log.i(LOG_TAG, "Disconnesso da $name ($endpointId)")
            TrekMeshBus.emitLog("Disconnesso da $name")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
        ) {
            if (endpointId in connectedEndpoints || endpointId in pendingEndpoints) {
                Log.d(LOG_TAG, "Endpoint $endpointId già gestito, ignoro")
                return
            }
            if (endpointNames.values.contains(info.endpointName)) {
                Log.d(LOG_TAG, "Dispositivo ${info.endpointName} già connesso via altro canale, ignoro")
                return
            }
            Log.i(LOG_TAG, "Endpoint trovato: ${info.endpointName} ($endpointId)")
            TrekMeshBus.emitLog("Dispositivo trovato: ${info.endpointName}, richiedo connessione...")
            pendingEndpoints.add(endpointId)
            endpointNames[endpointId] = info.endpointName
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            val name = endpointNames[endpointId] ?: endpointId
            Log.i(LOG_TAG, "Endpoint perso: $name ($endpointId)")
            TrekMeshBus.emitLog("Dispositivo perso: $name")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8)
            val type = raw.substringBefore("|")

            when (type) {
                TYPE_MSG -> handleIncomingMessage(endpointId, raw)
                TYPE_ACK -> handleIncomingAck(raw)
                else -> Log.w(LOG_TAG, "Tipo payload sconosciuto da $endpointId: $type")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncomingMessage(endpointId: String, raw: String) {
        // Wire: MSG|id|sender|ttl|encrypted_text
        val parts = raw.split("|", limit = 5)
        if (parts.size < 5) {
            Log.w(LOG_TAG, "MSG malformato da $endpointId: $raw")
            return
        }
        val (_, msgId, sender, ttlStr, encryptedText) = parts
        val ttl = ttlStr.toIntOrNull() ?: 0
        val text = CryptoHelper.decrypt(encryptedText) ?: run {
            Log.w(LOG_TAG, "Decifratura fallita per messaggio $msgId da $endpointId")
            return
        }

        if (!seenMessageIds.add(msgId)) {
            Log.d(LOG_TAG, "Messaggio duplicato ignorato: $msgId")
            return
        }

        Log.i(LOG_TAG, "Messaggio da $sender (TTL=$ttl): $text")
        TrekMeshBus.emitMessage(msgId, sender, text, isOwn = false)
        serviceScope.launch { db.messageDao().insert(MessageEntity(msgId, sender, ttl, text, "RECEIVED")) }

        sendAck(endpointId, msgId)

        if (ttl > 1) {
            val forward = MeshMessage(msgId, sender, ttl - 1, text)
            serviceScope.launch { persistMessage(forward, "RECEIVED") }
            forwardToAllExcept(forward, excludeEndpointId = endpointId)
        }
    }

    private fun handleIncomingAck(raw: String) {
        // Wire: ACK|originalMsgId|ackerName
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return
        val (_, originalMsgId, ackerName) = parts
        Log.i(LOG_TAG, "ACK ricevuto per $originalMsgId da $ackerName")
        serviceScope.launch { db.messageDao().updateStatus(originalMsgId, "DELIVERED") }
        val alreadyDelivered = !ownMessageIds.remove(originalMsgId)
        TrekMeshBus.updateMessageStatus(originalMsgId, "DELIVERED")
        if (!alreadyDelivered) {
            TrekMeshBus.emitLog("Messaggio consegnato a $ackerName")
        }
    }

    private fun sendAck(endpointId: String, originalMsgId: String) {
        val ack = "$TYPE_ACK|$originalMsgId|$localEndpointName"
        val payload = Payload.fromBytes(ack.toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
    }

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        serviceId = packageName
        localEndpointName = generateEndpointName()
        db = TrekMeshDatabase.getInstance(this)
        serviceScope.launch {
            seenMessageIds += db.messageDao().getAllIds()
        }
        listenForOutgoingMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startNetworking()
        return START_STICKY
    }

    private fun listenForOutgoingMessages() {
        serviceScope.launch {
            TrekMeshBus.outgoing.collect { raw ->
                val parts = raw.split("|", limit = 2)
                val msgId = parts[0]
                val text = parts.getOrElse(1) { "" }
                val msg = MeshMessage(msgId, localEndpointName, INITIAL_TTL, text)

                seenMessageIds.add(msgId)
                ownMessageIds.add(msgId)
                persistMessage(msg, "PENDING")
                TrekMeshBus.emitMessage(msgId, localEndpointName, text, isOwn = true)

                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("Nessun dispositivo connesso — messaggio bufferizzato")
                    return@collect
                }
                sendToAll(msg)
            }
        }
    }

    private fun sendToAll(msg: MeshMessage) {
        val payload = Payload.fromBytes(msg.toWireFormat().toByteArray(Charsets.UTF_8))
        connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
    }

    private fun forwardToAllExcept(msg: MeshMessage, excludeEndpointId: String) {
        val targets = connectedEndpoints - excludeEndpointId
        if (targets.isEmpty()) return
        val payload = Payload.fromBytes(msg.toWireFormat().toByteArray(Charsets.UTF_8))
        targets.forEach { connectionsClient.sendPayload(it, payload) }
        Log.d(LOG_TAG, "Forwarded ${msg.id} a ${targets.size} peer(s) (TTL=${msg.ttl})")
    }

    private suspend fun flushBufferTo(endpointId: String) {
        val buffered = db.messageDao().getAll()
        if (buffered.isEmpty()) return
        Log.i(LOG_TAG, "Flush di ${buffered.size} messaggi verso $endpointId")
        TrekMeshBus.emitLog("Invio ${buffered.size} messaggi bufferizzati a $endpointId...")
        buffered.forEach { entity ->
            if (entity.ttl > 0) {
                val msg = MeshMessage(entity.id, entity.sender, entity.ttl, entity.text)
                val payload = Payload.fromBytes(msg.toWireFormat().toByteArray(Charsets.UTF_8))
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
        db.messageDao().deleteExpired()
    }

    private suspend fun persistMessage(msg: MeshMessage, status: String) {
        db.messageDao().insert(msg.toEntity(status))
        db.messageDao().pruneOldest(MAX_BUFFER_SIZE)
    }

    private fun startNetworking() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient
            .startAdvertising(localEndpointName, serviceId, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                Log.i(LOG_TAG, "Advertising avviato")
                TrekMeshBus.emitLog("[$localEndpointName] In ascolto di altri dispositivi...")
            }
            .addOnFailureListener { e ->
                Log.e(LOG_TAG, "Advertising fallito", e)
                TrekMeshBus.emitLog("Errore advertising: ${e.message}")
            }

        connectionsClient
            .startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                Log.i(LOG_TAG, "Discovery avviato")
                TrekMeshBus.emitLog("Scansione dispositivi vicini avviata...")
            }
            .addOnFailureListener { e ->
                Log.e(LOG_TAG, "Discovery fallito", e)
                TrekMeshBus.emitLog("Errore discovery: ${e.message}")
            }
    }

    private fun generateEndpointName(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02X".format(it) }
        return "Hiker-$hex"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "TrekMesh Protezione",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Notifica di servizio P2P TrekMesh" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
        serviceScope.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
