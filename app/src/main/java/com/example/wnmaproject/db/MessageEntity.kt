package com.example.trekmesh.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val ttl: Int,
    val type: String,           // "INFO" | "SOS"
    val priority: Int,          // 1-3
    val text: String,
    val description: String = "",
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Double = 0.0,
    val status: String = "PENDING",
    val timestamp: Long = System.currentTimeMillis()
)
