package com.example.trekmesh

import android.util.Log

object BenchmarkLogger {

    private const val TAG = "TrekMesh-BENCH"
    private val timers = mutableMapOf<String, Long>()

    fun start(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        timers[label] = System.currentTimeMillis()
        Log.d(TAG, "[$label] START")
    }

    fun stop(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        val start = timers.remove(label) ?: return
        val elapsed = System.currentTimeMillis() - start
        val msg = "[$label] ${elapsed} ms"
        Log.d(TAG, msg)
        TrekMeshBus.emitLog("⏱ $msg")
    }

    fun log(msg: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        Log.d(TAG, msg)
        TrekMeshBus.emitLog("⏱ $msg")
    }
}
