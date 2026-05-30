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
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String
    private val connectedEndpoints = mutableSetOf<String>()
    private val seenMessageIds = mutableSetOf<String>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(LOG_TAG, "Connessione iniziata da ${connectionInfo.endpointName}")
            TrekMeshBus.emitLog("Connessione in entrata da ${connectionInfo.endpointName}, accetto...")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints.add(endpointId)
                TrekMeshBus.emitLog("Connesso a $endpointId")
                Log.i(LOG_TAG, "Connessione OK con $endpointId")
            } else {
                Log.w(LOG_TAG, "Connessione fallita con $endpointId: ${resolution.status.statusCode}")
                TrekMeshBus.emitLog("Connessione fallita con $endpointId (codice: ${resolution.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            Log.i(LOG_TAG, "Disconnesso da $endpointId")
            TrekMeshBus.emitLog("Disconnesso da $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo) {
            Log.i(LOG_TAG, "Endpoint trovato: ${info.endpointName} ($endpointId)")
            TrekMeshBus.emitLog("Dispositivo trovato: ${info.endpointName}, richiedo connessione...")
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(LOG_TAG, "Endpoint perso: $endpointId")
            TrekMeshBus.emitLog("Dispositivo perso: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val raw = String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8)
                val parts = raw.split("|", limit = 3)
                if (parts.size < 3) {
                    Log.w(LOG_TAG, "Payload malformato da $endpointId: $raw")
                    return
                }
                val (msgId, sender, text) = parts
                if (!seenMessageIds.add(msgId)) {
                    Log.d(LOG_TAG, "Messaggio duplicato ignorato: $msgId")
                    return
                }
                Log.i(LOG_TAG, "Messaggio da $sender: $text")
                TrekMeshBus.emitLog("$sender: $text")
            } else {
                Log.i(LOG_TAG, "Payload non BYTES da $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Nessuna gestione extra al momento
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
        serviceId = packageName
        localEndpointName = generateEndpointName()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        // Inizia il servizio in foreground con il tipo appropriato per Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: specifica il tipo di servizio foreground
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            // Android 12-13
            startForeground(NOTIFICATION_ID, notification)
        }

        startNetworking()
        listenForOutgoingMessages()
        return START_STICKY
    }

    private fun listenForOutgoingMessages() {
        serviceScope.launch {
            TrekMeshBus.outgoing.collect { raw ->
                if (connectedEndpoints.isEmpty()) {
                    TrekMeshBus.emitLog("Nessun dispositivo connesso")
                    return@collect
                }
                // raw = "<id>|<testo>", aggiungiamo il mittente: "<id>|<sender>|<testo>"
                val parts = raw.split("|", limit = 2)
                val msgId = parts[0]
                val text = parts.getOrElse(1) { "" }
                val fullPayload = "$msgId|$localEndpointName|$text"
                seenMessageIds.add(msgId)
                val payload = Payload.fromBytes(fullPayload.toByteArray())
                connectedEndpoints.forEach { endpointId ->
                    connectionsClient.sendPayload(endpointId, payload)
                }
                TrekMeshBus.emitLog("Tu: $text")
            }
        }
    }

    private fun startNetworking() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

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
        val suffix = Random.nextInt(0x10000)
        return "Hiker-%04X".format(suffix)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "TrekMesh Protezione",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica di servizio P2P TrekMesh"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TrekMesh")
            .setContentText("TrekMesh sta proteggendo la tua escursione")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
