package com.example.trekmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_alerts")
data class PendingAlertEntity(
    @PrimaryKey val messageId: String,
    val sender: String,
    val type: String,
    val priority: Int,
    val text: String,
    val description: String,
    val rifugioName: String,
    val timestamp: Long = System.currentTimeMillis()
)
