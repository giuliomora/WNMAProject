package com.example.trekmesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (hasCriticalPermissions(context)) {
            context.startForegroundService(Intent(context, TrekMeshService::class.java))
        } else {
            showPermissionNeededNotification(context)
        }
    }

    private fun hasCriticalPermissions(context: Context): Boolean {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionNeededNotification(context: Context) {
        val channelId = "trekmesh_boot_channel"
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(channelId, "TrekMesh Avvio", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avvisi di avvio automatico TrekMesh" }
        )
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("TrekMesh: permessi necessari")
            .setContentText("Apri l'app per attivare la protezione mesh dopo il riavvio.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(999, notification)
    }
}
