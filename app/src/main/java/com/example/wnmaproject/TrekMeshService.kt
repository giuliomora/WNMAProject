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

class TrekMeshService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "trekmesh_p2p_channel"
        private const val NOTIFICATION_ID = 1
        private const val LOG_TAG = "TrekMeshService"
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var serviceId: String
    private lateinit var localEndpointName: String

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(LOG_TAG, "Connessione iniziata da ${connectionInfo.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val message = "Ping ricevuto da $localEndpointName"
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(message.toByteArray()))
                Log.i(LOG_TAG, "Connessione OK con $endpointId, inviato ping")
            } else {
                Log.w(LOG_TAG, "Connessione fallita con $endpointId: ${resolution.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(LOG_TAG, "Disconnesso da $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: com.google.android.gms.nearby.connection.DiscoveredEndpointInfo) {
            Log.i(LOG_TAG, "Endpoint trovato: ${info.endpointName} ($endpointId)")
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(LOG_TAG, "Endpoint perso: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val data = payload.asBytes() ?: ByteArray(0)
                val text = String(data, Charsets.UTF_8)
                Log.i(LOG_TAG, "Payload da $endpointId: $text")
                TrekMeshBus.emitLog("Da $endpointId: $text")
            } else {
                Log.i(LOG_TAG, "Payload non BYTES da $endpointId")
                TrekMeshBus.emitLog("Payload non BYTES da $endpointId")
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
        return START_STICKY
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
            .addOnSuccessListener { Log.i(LOG_TAG, "Advertising avviato") }
            .addOnFailureListener { e -> Log.e(LOG_TAG, "Advertising fallito", e) }

        connectionsClient
            .startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { Log.i(LOG_TAG, "Discovery avviato") }
            .addOnFailureListener { e -> Log.e(LOG_TAG, "Discovery fallito", e) }
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
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
