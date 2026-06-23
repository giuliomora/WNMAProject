package com.example.trekmesh

import android.content.Context
import android.os.BatteryManager
import android.util.Log

object BenchmarkLogger {

    private const val TAG = "TrekMesh-BENCH"
    private val timers = mutableMapOf<String, Long>()

    fun start(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        timers[label] = System.currentTimeMillis()
        val msg = "[$label] START ts=${System.currentTimeMillis()}"
        Log.d(TAG, msg)
        TrekMeshBus.emitBench(msg)
    }

    fun stop(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        val start = timers.remove(label) ?: return
        val elapsed = System.currentTimeMillis() - start
        val msg = "[$label] DONE ${elapsed} ms"
        Log.d(TAG, msg)
        TrekMeshBus.emitBench(msg)
    }

    fun log(msg: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        Log.d(TAG, msg)
        TrekMeshBus.emitBench(msg)
    }

    fun logBattery(context: Context) {
        if (!BuildConfig.BENCHMARK_MODE) return
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        log("BATTERY: $pct%")
    }
}
