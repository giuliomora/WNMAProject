package com.example.trekmesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SafetyTimerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SAFETY_TIMER_EXPIRED = "com.example.trekmesh.ACTION_SAFETY_TIMER_EXPIRED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SAFETY_TIMER_EXPIRED) {
            Log.i("SafetyTimerReceiver", "Safety timer expired! Triggering SOS via service.")

            // Inviamo l'azione direttamente al servizio tramite Intent.
            // Questo è più affidabile di un Flow se il servizio deve essere avviato.
            val serviceIntent = Intent(context, TrekMeshService::class.java).apply {
                action = TrekMeshService.ACTION_AUTO_SOS
            }
            context.startForegroundService(serviceIntent)

            // Azzeriamo il timer nel bus per aggiornare la UI se l'app è aperta
            TrekMeshBus.updateSafetyTimer(0)
        }
    }
}
