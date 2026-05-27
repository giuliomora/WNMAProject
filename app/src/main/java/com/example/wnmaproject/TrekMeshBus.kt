package com.example.trekmesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TrekMeshBus {
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs = _logs.asSharedFlow()

    fun emitLog(message: String) {
        _logs.tryEmit(message)
    }
}

