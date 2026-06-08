package com.example.trekmesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatMessage(
    val id: String,
    val label: String,
    val text: String,
    val status: String  // "PENDING" | "DELIVERED" | "RECEIVED"
)

object TrekMeshBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _outgoing = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val outgoing = _outgoing.asSharedFlow()

    fun emitLog(message: String) {
        _logs.update { it + message }
    }

    fun emitMessage(id: String, sender: String, text: String, isOwn: Boolean) {
        val label = if (isOwn) "Tu" else sender
        val status = if (isOwn) "PENDING" else "RECEIVED"
        _messages.update { it + ChatMessage(id, label, text, status) }
    }

    fun updateMessageStatus(id: String, status: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun sendMessage(message: String) {
        val id = java.util.UUID.randomUUID().toString()
        _outgoing.tryEmit("$id|$message")
    }
}
