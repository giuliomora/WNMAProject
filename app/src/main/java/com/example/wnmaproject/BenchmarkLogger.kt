package com.example.trekmesh

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BenchmarkLogger {

    private const val TAG = "TrekMesh-BENCH"
    private val timers = mutableMapOf<String, Long>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** Returns the log file (in app's external files dir), or null if unavailable. */
    fun logFile(context: Context): File? {
        if (!BuildConfig.BENCHMARK_MODE) return null
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "trekmesh_benchmark.log")
    }

    private var fileRef: File? = null

    fun init(context: Context) {
        if (!BuildConfig.BENCHMARK_MODE) return
        fileRef = logFile(context)
        fileRef?.let { f ->
            if (!f.exists()) f.createNewFile()
            f.appendText("\n\n=== SESSION START ${fmt.format(Date())} ===\n")
        }
    }

    private fun emit(msg: String) {
        val stamped = "${fmt.format(Date())}  $msg"
        Log.d(TAG, stamped)
        TrekMeshBus.emitBench(stamped)
        try { fileRef?.appendText("$stamped\n") } catch (_: Exception) {}
    }

    fun start(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        timers[label] = System.currentTimeMillis()
        emit("[$label] START ts=${System.currentTimeMillis()}")
    }

    fun stop(label: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        val start = timers.remove(label) ?: return
        emit("[$label] DONE ${System.currentTimeMillis() - start}ms")
    }

    fun log(msg: String) {
        if (!BuildConfig.BENCHMARK_MODE) return
        emit(msg)
    }

    fun logBattery(context: Context) {
        if (!BuildConfig.BENCHMARK_MODE) return
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        emit("BATTERY: $pct%")
    }
}
