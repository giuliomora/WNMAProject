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
import kotlin.random.Random
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
    }

    private data class MeshMessage(
        val id: String,
        val sender: String,
        val ttl: Int,
        val text: String
    ) {
        fun toWireFormat() = "$id|$sender|$ttl|$text"
        fun toEntity() = MessageEntity(id, sender, ttl, text)
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String
    private lateinit var db: TrekMeshDatabase

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingEndpoints = mutableSetOf<String>()
    // endpointId -> endpointName, per deduplicare lo stesso dispositivo su BT e WiFi
    private val endpointNames = mutableMapOf<String, String>()
    // Popolato all'avvio dal DB; aggiornato in memoria durante la sessione
    private val seenMessageIds = mutableSetOf<String>()

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
                // Scarta connessioni duplicate verso lo stesso dispositivo (es. BT + WiFi simultanei)
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
            connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            val name = endpointNames[endpointId] ?: endpointId
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
            val parts = raw.split("|", limit = 4)
            if (parts.size < 4) {
                Log.w(LOG_TAG, "Payload malformato da $endpointId: $raw")
                return
            }
            val (msgId, sender, ttlStr, text) = parts
            val ttl = ttlStr.toIntOrNull() ?: 0

            if (!seenMessageIds.add(msgId)) {
                Log.d(LOG_TAG, "Messaggio duplicato ignorato: $msgId")
                return
            }

            Log.i(LOG_TAG, "Messaggio da $sender (TTL=$ttl): $text")
            TrekMeshBus.emitMessage(sender, text, isOwn = false)

            if (ttl > 1) {
                val forward = MeshMessage(msgId, sender, ttl - 1, text)
                serviceScope.launch { persistMessage(forward) }
                forwardToAllExcept(forward, excludeEndpointId = endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
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
                persistMessage(msg)
                TrekMeshBus.emitMessage(localEndpointName, text, isOwn = true)

                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("Nessun dispositivo connesso — messaggio bufferizzato")
                    return@collect
                }
                sendToAll(msg)
            }
        }
    }

    private fun sendToAll(msg: MeshMessage) {
        val payload = Payload.fromBytes(msg.toWireFormat().toByteArray())
        connectedEndpoints.forEach { connectionsClient.sendPayload(it, payload) }
    }

    private fun forwardToAllExcept(msg: MeshMessage, excludeEndpointId: String) {
        val targets = connectedEndpoints - excludeEndpointId
        if (targets.isEmpty()) return
        val payload = Payload.fromBytes(msg.toWireFormat().toByteArray())
        targets.forEach { connectionsClient.sendPayload(it, payload) }
        Log.d(LOG_TAG, "Forwarded ${msg.id} a ${targets.size} peer(s) (TTL=${msg.ttl})")
    }

    // Store-and-forward: invia l'intero buffer persistito al peer appena connesso
    private suspend fun flushBufferTo(endpointId: String) {
        val buffered = db.messageDao().getAll()
        if (buffered.isEmpty()) return
        val name = endpointNames[endpointId] ?: endpointId
        Log.i(LOG_TAG, "Flush di ${buffered.size} messaggi verso $name ($endpointId)")
        TrekMeshBus.emitLog("Invio ${buffered.size} messaggi bufferizzati a $name...")
        buffered.forEach { entity ->
            if (entity.ttl > 0) {
                val wire = "${entity.id}|${entity.sender}|${entity.ttl}|${entity.text}"
                val payload = Payload.fromBytes(wire.toByteArray())
                connectionsClient.sendPayload(endpointId, payload)
            }
        }
        db.messageDao().deleteExpired()
    }

    private suspend fun persistMessage(msg: MeshMessage) {
        db.messageDao().insert(msg.toEntity())
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

    private fun generateEndpointName(): String = "Hiker-%04X".format(Random.nextInt(0x10000))

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
