package com.example.trekmesh

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.trekmesh.db.TrekMeshDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID        = "msg_id"
        const val EXTRA_SENDER    = "msg_sender"
        const val EXTRA_TYPE      = "msg_type"
        const val EXTRA_PRIORITY  = "msg_priority"
        const val EXTRA_TEXT      = "msg_text"
        const val EXTRA_DESC      = "msg_desc"
        const val EXTRA_IMAGE     = "msg_image"
        const val EXTRA_STATUS    = "msg_status"
        const val EXTRA_TTL       = "msg_ttl"
        const val EXTRA_TIMESTAMP = "msg_timestamp"
        const val EXTRA_LAT       = "msg_lat"
        const val EXTRA_LON       = "msg_lon"
        const val EXTRA_ALT       = "msg_alt"
        const val EXTRA_IS_OWN    = "msg_is_own"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_detail)

        val type      = intent.getStringExtra(EXTRA_TYPE) ?: "INFO"
        val priority  = intent.getIntExtra(EXTRA_PRIORITY, 1)
        val sender    = intent.getStringExtra(EXTRA_SENDER) ?: ""
        val text      = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val desc      = intent.getStringExtra(EXTRA_DESC) ?: ""
        val imagePath = intent.getStringExtra(EXTRA_IMAGE)
        val status    = intent.getStringExtra(EXTRA_STATUS) ?: ""
        val ttl       = intent.getIntExtra(EXTRA_TTL, 0)
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
        val lat       = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon       = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val alt       = intent.getDoubleExtra(EXTRA_ALT, 0.0)
        val isOwn     = intent.getBooleanExtra(EXTRA_IS_OWN, false)
        val msgId     = intent.getStringExtra(EXTRA_ID) ?: ""

        val isSos       = type == "SOS"
        val isBroadcast = type == "BROADCAST"

        findViewById<ImageButton>(R.id.btn_detail_back).setOnClickListener { finish() }

        // Titolo toolbar
        findViewById<TextView>(R.id.tv_detail_title).text = when {
            isSos       -> "🆘 SOS"
            isBroadcast -> "📡 Broadcast"
            else        -> "📩 Messaggio INFO"
        }

        // Badge tipo/priorità
        val badgeColor = when {
            isSos       -> 0xCCF44336.toInt()
            isBroadcast -> 0xCCFF9800.toInt()
            else        -> 0xCC2196F3.toInt()
        }
        findViewById<TextView>(R.id.tv_detail_badge).apply {
            this.text = "$type  P$priority"
            setBackgroundColor(badgeColor)
        }

        // Badge stato
        val (statusText, statusColor) = when (status) {
            "DELIVERED" -> "✓ Consegnato" to 0xCC4CAF50.toInt()
            "PENDING"   -> "⏳ In attesa"  to 0xCC888888.toInt()
            "RECEIVED"  -> "📥 Ricevuto"   to 0xCC2196F3.toInt()
            else        -> status          to 0xCC888888.toInt()
        }
        findViewById<TextView>(R.id.tv_detail_status).apply {
            this.text = statusText
            setBackgroundColor(statusColor)
        }

        // Immagine
        if (!imagePath.isNullOrBlank()) {
            val bmp = BitmapFactory.decodeFile(imagePath)
            if (bmp != null) {
                val iv = findViewById<ImageView>(R.id.iv_detail_image)
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
            }
        }

        // Testo
        findViewById<TextView>(R.id.tv_detail_text).text = text

        // Descrizione
        if (desc.isNotBlank()) {
            findViewById<TextView>(R.id.label_detail_desc).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_detail_desc).apply {
                this.text = desc
                visibility = View.VISIBLE
            }
        }

        // Metadati
        findViewById<TextView>(R.id.tv_detail_sender).text = sender

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tv_detail_timestamp).text = sdf.format(Date(timestamp))

        findViewById<TextView>(R.id.tv_detail_ttl).text = "$ttl salti rimanenti"

        // GPS
        if (lat != 0.0 || lon != 0.0) {
            val altStr = if (alt != 0.0) "  alt %.0fm".format(alt) else ""
            findViewById<TextView>(R.id.tv_detail_gps).text =
                "%.5f, %.5f%s".format(lat, lon, altStr)
            findViewById<LinearLayout>(R.id.row_detail_gps).visibility = View.VISIBLE

            findViewById<Button>(R.id.btn_detail_maps).setOnClickListener {
                val label = sender.ifBlank { "Posizione" }
                val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Nessuna app mappe installata", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Elimina (solo messaggi INFO inviati da me)
        val btnDelete = findViewById<Button>(R.id.btn_detail_delete)
        if (isOwn && type == "INFO") {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Elimina messaggio")
                    .setMessage("Vuoi eliminare questo messaggio? Verrà rimosso anche dai dispositivi degli altri utenti nella rete.")
                    .setPositiveButton("Elimina") { _, _ ->
                        lifecycleScope.launch {
                            TrekMeshDatabase.getInstance(applicationContext).messageDao().deleteById(msgId)
                        }
                        TrekMeshBus.deleteMessageById(msgId)
                        finish()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }

        // Segnala risolto (solo messaggi INFO ricevuti)
        val btnResolve = findViewById<Button>(R.id.btn_detail_resolve)
        if (!isOwn && type == "INFO") {
            val voted = getSharedPreferences("trekmesh_votes_mine", MODE_PRIVATE)
                .getBoolean(msgId, false)
            btnResolve.visibility = View.VISIBLE
            if (voted) {
                btnResolve.text = "✓ Già segnalato"
                btnResolve.isEnabled = false
            } else {
                btnResolve.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Segnala come risolto")
                        .setMessage("Quando 2 utenti segnalano il messaggio come risolto, verrà eliminato dalla rete.")
                        .setPositiveButton("Segnala") { _, _ ->
                            getSharedPreferences("trekmesh_votes_mine", MODE_PRIVATE)
                                .edit().putBoolean(msgId, true).apply()
                            TrekMeshBus.sendResolveVote(msgId)
                            Toast.makeText(this, "Segnalazione inviata", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
        }
    }
}
