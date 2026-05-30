package com.example.trekmesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TrekMeshBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _outgoing = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val outgoing = _outgoing.asSharedFlow()

    fun emitLog(message: String) {
        _logs.update { it + message }
    }

    fun sendMessage(message: String) {
        val id = java.util.UUID.randomUUID().toString()
        _outgoing.tryEmit("$id|$message")
    }
}

