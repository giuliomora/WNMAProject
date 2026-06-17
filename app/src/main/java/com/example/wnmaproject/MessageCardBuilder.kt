package com.example.trekmesh

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private const val INITIAL_TTL = 7

fun buildMessageCard(context: Context, msg: ChatMessage): View {
    val isOwn  = msg.label == "Tu"
    val isSos  = msg.type == "SOS"
    val isBroadcast = msg.type == "BROADCAST"
    val isNearby = !isOwn && isSos && msg.ttl == INITIAL_TTL - 1

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 12, 16, 12)
        setBackgroundColor(when {
            isNearby    -> 0x44F44336.toInt()
            isSos       -> 0x22F44336.toInt()
            isBroadcast -> 0x22FF9800.toInt() // Arancione per broadcast
            else        -> 0x11FFFFFF.toInt()
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 4, 0, 4) }
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

    if (msg.description.isNotBlank()) {
        card.addView(TextView(context).apply {
            text = msg.description
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, 4, 0, 0)
        })
    }

    msg.imagePath?.let { path ->
        BitmapFactory.decodeFile(path)?.let { bmp ->
            card.addView(ImageView(context).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 240
                ).apply { topMargin = 8 }
            })
        }
    }

    return card
}
