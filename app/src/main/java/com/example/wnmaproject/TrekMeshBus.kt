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
    val imagePath: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Double = 0.0
)

data class ChatMessage(
    val id: String,
    val label: String,
    val type: String,
    val priority: Int,
    val text: String,
    val description: String,
    val imagePath: String?,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val status: String,     // "PENDING" | "DELIVERED" | "RECEIVED"
    val ttl: Int = 0,       // TTL residuo al momento della ricezione (0 = messaggio proprio)
    val timestamp: Long = System.currentTimeMillis()
)

enum class SafetyTimerAction { START_30M, START_1H, START_2H, STOP }

object TrekMeshBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    // Timer di sicurezza: secondi rimanenti. 0 = disattivato.
    private val _safetyTimer = MutableStateFlow<Int>(0)
    val safetyTimer = _safetyTimer.asStateFlow()

    private val _safetyActions = MutableSharedFlow<SafetyTimerAction>(extraBufferCapacity = 8)
    val safetyActions = _safetyActions.asSharedFlow()

    private val _outgoing = MutableSharedFlow<OutgoingMessage>(extraBufferCapacity = 64)
    val outgoing = _outgoing.asSharedFlow()

    data class SosStatusUpdate(val msgId: String, val status: String, val rifugioName: String)
    private val _sosStatusUpdates = MutableSharedFlow<SosStatusUpdate>(extraBufferCapacity = 16)
    val sosStatusUpdates = _sosStatusUpdates.asSharedFlow()

    fun sendSosStatusUpdate(msgId: String, status: String, rifugioName: String) {
        _sosStatusUpdates.tryEmit(SosStatusUpdate(msgId, status, rifugioName))
    }

    private val _resolveVotes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val resolveVotes = _resolveVotes.asSharedFlow()

    private val _deleteMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val deleteMessage = _deleteMessage.asSharedFlow()

    fun sendResolveVote(msgId: String) { _resolveVotes.tryEmit(msgId) }

    fun deleteMessageById(msgId: String) {
        _messages.update { it.filter { m -> m.id != msgId } }
        _deleteMessage.tryEmit(msgId)
    }

    fun removeMessageFromMemory(msgId: String) {
        _messages.update { it.filter { m -> m.id != msgId } }
    }

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
        lat: Double,
        lon: Double,
        alt: Double,
        isOwn: Boolean,
        ttl: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val label = if (isOwn) "Tu" else sender
        val status = if (isOwn) "PENDING" else "RECEIVED"
        _messages.update { it + ChatMessage(id, label, type, priority, text, description, imagePath, lat, lon, alt, status, ttl, timestamp) }
        if (!isOwn) incrementUnread()
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

    private val _peerCount = MutableStateFlow(0)
    val peerCount = _peerCount.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount = _unreadCount.asStateFlow()

    fun incrementUnread() { _unreadCount.update { it + 1 } }
    fun resetUnread()     { _unreadCount.value = 0 }

    fun updatePeerCount(count: Int) {
        _peerCount.value = count
    }

    fun updateSafetyTimer(seconds: Int) {
        _safetyTimer.value = seconds
    }

    fun triggerSafetyAction(action: SafetyTimerAction) {
        _safetyActions.tryEmit(action)
    }

    fun sendMessage(msg: OutgoingMessage) {
        _outgoing.tryEmit(msg)
    }
}
