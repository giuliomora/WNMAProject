package com.example.trekmesh

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val INITIAL_TTL = 7

fun buildMessageCard(context: Context, msg: ChatMessage): View {
    // Click apre il dettaglio
    fun openDetail() {
        context.startActivity(Intent(context, MessageDetailActivity::class.java).apply {
            putExtra(MessageDetailActivity.EXTRA_ID,        msg.id)
            putExtra(MessageDetailActivity.EXTRA_SENDER,    msg.label)
            putExtra(MessageDetailActivity.EXTRA_TYPE,      msg.type)
            putExtra(MessageDetailActivity.EXTRA_PRIORITY,  msg.priority)
            putExtra(MessageDetailActivity.EXTRA_TEXT,      msg.text)
            putExtra(MessageDetailActivity.EXTRA_DESC,      msg.description)
            putExtra(MessageDetailActivity.EXTRA_IMAGE,     msg.imagePath)
            putExtra(MessageDetailActivity.EXTRA_STATUS,    msg.status)
            putExtra(MessageDetailActivity.EXTRA_TTL,       msg.ttl)
            putExtra(MessageDetailActivity.EXTRA_TIMESTAMP, msg.timestamp)
            putExtra(MessageDetailActivity.EXTRA_LAT,       msg.lat)
            putExtra(MessageDetailActivity.EXTRA_LON,       msg.lon)
            putExtra(MessageDetailActivity.EXTRA_ALT,       msg.alt)
        })
    }
    val isOwn  = msg.label == "Tu"
    val isSos  = msg.type == "SOS"
    val isBroadcast = msg.type == "BROADCAST"
    val isNearby = !isOwn && isSos && msg.ttl == INITIAL_TTL - 1
    val isAcknowledged = msg.status == "ACKNOWLEDGED"
    val isResolved     = msg.status == "RESOLVED"
    val isRifugio = UserRolePrefs.getRole(context) == UserRole.RIFUGIO

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 12, 16, 12)
        setBackgroundColor(when {
            isResolved     -> 0x224CAF50.toInt()  // verde scuro
            isAcknowledged -> 0x33FF9800.toInt()  // arancione
            isNearby       -> 0x44F44336.toInt()
            isSos          -> 0x22F44336.toInt()
            isBroadcast    -> 0x22FF9800.toInt()
            else           -> 0x11FFFFFF.toInt()
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 4, 0, 4) }
        isClickable = true
        isFocusable = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .getDrawable(0)
        setOnClickListener { openDetail() }
    }

    // Header
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    header.addView(TextView(context).apply {
        text = msg.label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (isSos) 0xFFFF5252.toInt() else 0xFF4FC3F7.toInt())
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })

    if (isNearby) {
        header.addView(TextView(context).apply {
            text = "📍 VICINO A TE"
            textSize = 10f
            setPadding(8, 2, 8, 2)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCCFF5722.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
        })
    }

    header.addView(TextView(context).apply {
        text = "${msg.type} P${msg.priority}"
        textSize = 10f
        setPadding(8, 2, 8, 2)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(when {
            isSos       -> 0xCCF44336.toInt()
            isBroadcast -> 0xCCFF9800.toInt()
            else        -> 0xCC2196F3.toInt()
        })
    })

    if (isOwn) {
        header.addView(TextView(context).apply {
            text = when (msg.status) {
                "PENDING"   -> " ⏳"
                "DELIVERED" -> " ✓"
                else        -> ""
            }
            textSize = 12f
        })
    }

    card.addView(header)

    card.addView(TextView(context).apply {
        text = msg.text
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(0, 6, 0, 0)
    })

    // Badge stato SOS (acknowledged/resolved) — visibile a tutti
    if (isSos && (isAcknowledged || isResolved)) {
        card.addView(TextView(context).apply {
            text = if (isResolved) "✅ Risolto" else "🟠 Preso in carico"
            textSize = 11f
            setTextColor(if (isResolved) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 0)
        })
    }

    // Bottoni gestione SOS — solo per rifugi
    if (isSos && isRifugio) {
        val localName = UserRolePrefs.getStoredRifugioName(context) ?: "Rifugio"
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        when {
            !isAcknowledged && !isResolved -> {
                row.addView(Button(context).apply {
                    text = "Prendi in carico"
                    textSize = 12f
                    setOnClickListener {
                        TrekMeshBus.sendSosStatusUpdate(msg.id, "ACKNOWLEDGED", localName)
                        TrekMeshBus.updateMessageStatus(msg.id, "ACKNOWLEDGED")
                    }
                })
            }
            isAcknowledged -> {
                row.addView(Button(context).apply {
                    text = "Segna come risolto"
                    textSize = 12f
                    setOnClickListener {
                        TrekMeshBus.sendSosStatusUpdate(msg.id, "RESOLVED", localName)
                        TrekMeshBus.updateMessageStatus(msg.id, "RESOLVED")
                    }
                })
            }
        }
        if (row.childCount > 0) card.addView(row)
    }

    // Footer: TTL residuo per messaggi ricevuti
    if (!isOwn) {
        val ttlColor = when {
            msg.ttl >= 5 -> 0xFF4CAF50.toInt()  // verde
            msg.ttl >= 2 -> 0xFFFF9800.toInt()  // arancione
            else         -> 0xFFF44336.toInt()  // rosso
        }
        val ttlLabel = when {
            msg.ttl <= 0 -> "scaduto"
            msg.ttl == 1 -> "1 salto"
            else         -> "${msg.ttl} salti"
        }
        card.addView(TextView(context).apply {
            text = "↔ $ttlLabel"
            textSize = 10f
            setTextColor(ttlColor)
            setPadding(0, 6, 0, 0)
        })
    }

    return card
}
