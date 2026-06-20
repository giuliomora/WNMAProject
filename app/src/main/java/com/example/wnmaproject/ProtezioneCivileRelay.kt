package com.example.trekmesh

import android.util.Log
import com.example.trekmesh.db.PendingAlertDao
import com.example.trekmesh.db.PendingAlertEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ProtezioneCivileRelay {

    private const val LOG_TAG = "PCRelay"
    private const val ENDPOINT_URL = "https://httpbin.org/post"

    suspend fun sendAlert(
        messageId: String,
        sender: String,
        type: String,
        priority: Int,
        text: String,
        description: String,
        rifugioName: String,
        dao: PendingAlertDao
    ): Boolean {
        val sent = trySend(messageId, sender, type, priority, text, description, rifugioName)
        if (!sent) {
            dao.insert(PendingAlertEntity(messageId, sender, type, priority, text, description, rifugioName))
            Log.w(LOG_TAG, "SOS $messageId salvato in coda per retry")
        }
        return sent
    }

    suspend fun retryPending(dao: PendingAlertDao) {
        val pending = dao.getAll()
        if (pending.isEmpty()) return
        Log.i(LOG_TAG, "Retry di ${pending.size} alert pendenti...")
        TrekMeshBus.emitLog("Connessione ripristinata — reinoltro ${pending.size} SOS in coda...")
        for (alert in pending) {
            val sent = trySend(
                messageId = alert.messageId,
                sender = alert.sender,
                type = alert.type,
                priority = alert.priority,
                text = alert.text,
                description = alert.description,
                rifugioName = alert.rifugioName
            )
            if (sent) {
                dao.delete(alert.messageId)
                TrekMeshBus.emitLog("SOS ${alert.messageId.take(8)}… reinoltrato alla Protezione Civile ✓")
            } else {
                TrekMeshBus.emitLog("Reinoltro SOS ${alert.messageId.take(8)}… fallito, rimane in coda")
            }
        }
    }

    private suspend fun trySend(
        messageId: String,
        sender: String,
        type: String,
        priority: Int,
        text: String,
        description: String,
        rifugioName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("messageId", messageId)
                put("sender", sender)
                put("rifugio", rifugioName)
                put("type", type)
                put("priority", priority)
                put("text", text)
                put("description", description)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            val url = URL(ENDPOINT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Api-Key", BuildConfig.PC_API_KEY)
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.i(LOG_TAG, "SOS inoltrato alla Protezione Civile (HTTP $code)")
                true
            } else {
                Log.w(LOG_TAG, "Risposta inattesa dalla Protezione Civile: HTTP $code")
                false
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Errore inoltro SOS alla Protezione Civile", e)
            TrekMeshBus.emitLog("Errore relay PC: ${e.javaClass.simpleName} — ${e.message?.take(80)}")
            false
        }
    }
}
