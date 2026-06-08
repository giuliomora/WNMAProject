package com.example.trekmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val ttl: Int,
    val text: String,
    val status: String = "PENDING",
    val timestamp: Long = System.currentTimeMillis()
)
