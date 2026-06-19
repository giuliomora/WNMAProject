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
import androidx.appcompat.app.AppCompatActivity
import java.io.File
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
                val mapsIntent = Intent(Intent.ACTION_VIEW, uri)
                if (mapsIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapsIntent)
                }
            }
        }
    }
}
