package com.example.trekmesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OutgoingMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,       // "INFO" | "SOS"
    val priority: Int,      // 1-3
    val text: String,
    val description: String = "",
    val imagePath: String? = null
)

data class ChatMessage(
    val id: String,
    val label: String,
    val type: String,
    val priority: Int,
    val text: String,
    val description: String,
    val imagePath: String?,
    val status: String,     // "PENDING" | "DELIVERED" | "RECEIVED"
    val ttl: Int = 0,       // TTL residuo al momento della ricezione (0 = messaggio proprio)
    val timestamp: Long = System.currentTimeMillis()
)

object TrekMeshBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _outgoing = MutableSharedFlow<OutgoingMessage>(extraBufferCapacity = 64)
    val outgoing = _outgoing.asSharedFlow()

    fun emitLog(message: String) {
        _logs.update { it + message }
    }

    fun emitMessage(
        id: String,
        sender: String,
        type: String,
        priority: Int,
        text: String,
        description: String,
        imagePath: String?,
        isOwn: Boolean,
        ttl: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val label = if (isOwn) "Tu" else sender
        val status = if (isOwn) "PENDING" else "RECEIVED"
        _messages.update { it + ChatMessage(id, label, type, priority, text, description, imagePath, status, ttl, timestamp) }
    }

    fun updateMessageStatus(id: String, status: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun updateMessageImage(id: String, imagePath: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(imagePath = imagePath) else it }
        }
    }

    fun sendMessage(msg: OutgoingMessage) {
        _outgoing.tryEmit(msg)
    }
}
