package com.example.trekmesh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ProtezioneCivileRelay {

    private const val LOG_TAG = "PCRelay"

    // Sostituire con l'endpoint reale della Protezione Civile o del backend di aggregazione.
    private const val ENDPOINT_URL = "https://httpbin.org/post"

    suspend fun sendAlert(
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
            false
        }
    }
}
