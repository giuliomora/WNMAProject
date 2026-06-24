package com.example.trekmesh

import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BenchmarkFragment : Fragment() {

    private var batteryStartPct = -1
    private var batteryStartTime = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_benchmark, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvPeers = view.findViewById<TextView>(R.id.tv_bench_peers)
        val tvMode  = view.findViewById<TextView>(R.id.tv_bench_mode)
        val tvBatt  = view.findViewById<TextView>(R.id.tv_battery)

        val bm = requireContext().getSystemService(BatteryManager::class.java)
        fun battery() = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        fun refreshBattery() { tvBatt.text = "🔋 ${battery()}%" }
        refreshBattery()

        // Live peer count
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.peerCount.collect { count ->
                tvPeers.text = "● Peers: $count"
                tvPeers.setTextColor(if (count > 0) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
            }
        }

        // Scan mode label from last SCAN_START entry
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.benchmarkLog.collect { lines ->
                val last = lines.lastOrNull { it.contains("SCAN_START") }
                tvMode.text = when {
                    last?.contains("HIGH_POWER") == true -> "Mode: HIGH POWER (BT+WiFi)"
                    last?.contains("LOW_POWER")  == true -> "Mode: LOW POWER (BT only)"
                    else -> "Mode: —"
                }
            }
        }

        // ── DISCOVERY & RECOVERY ──
        view.findViewById<Button>(R.id.btn_rediscovery).setOnClickListener {
            BenchmarkLogger.log("--- Force Re-Discovery triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.REDISCOVERY)
        }
        view.findViewById<Button>(R.id.btn_recovery_10s).setOnClickListener {
            BenchmarkLogger.log("--- Recovery test 10s blackout triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.RECOVERY_10S)
        }
        view.findViewById<Button>(R.id.btn_recovery_30s).setOnClickListener {
            BenchmarkLogger.log("--- Recovery test 30s blackout triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.RECOVERY_30S)
        }
        view.findViewById<Button>(R.id.btn_rssi_snap).setOnClickListener {
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.RSSI_SNAPSHOT)
        }

        // ── THROUGHPUT ──
        view.findViewById<Button>(R.id.btn_throughput_100k).setOnClickListener {
            BenchmarkLogger.log("--- Throughput test 100KB triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.THROUGHPUT_100K)
        }
        view.findViewById<Button>(R.id.btn_throughput_500k).setOnClickListener {
            BenchmarkLogger.log("--- Throughput test 500KB triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.THROUGHPUT_500K)
        }

        // ── TRAFFIC & STRESS ──
        view.findViewById<Button>(R.id.btn_packet_loss).setOnClickListener {
            refreshBattery()
            TrekMeshBus.triggerBenchPing(50)
        }
        view.findViewById<Button>(R.id.btn_stress).setOnClickListener {
            BenchmarkLogger.log("--- Stress test 10 msgs triggered ---")
            TrekMeshBus.triggerBenchControl(TrekMeshBus.BenchControl.STRESS_10_MSGS)
        }

        // ── BATTERY DIFFERENTIAL ──
        view.findViewById<Button>(R.id.btn_battery_start).setOnClickListener {
            batteryStartPct  = battery()
            batteryStartTime = System.currentTimeMillis()
            BenchmarkLogger.log("BATTERY_DIFF START: $batteryStartPct% at ts=$batteryStartTime")
            refreshBattery()
        }
        view.findViewById<Button>(R.id.btn_battery_end).setOnClickListener {
            if (batteryStartPct < 0) {
                BenchmarkLogger.log("BATTERY_DIFF: press 'Battery Start' first")
                return@setOnClickListener
            }
            val nowPct = battery()
            val elapsed = System.currentTimeMillis() - batteryStartTime
            val diff    = batteryStartPct - nowPct
            val mins    = elapsed / 60_000
            val rate    = if (mins > 0) "%.2f".format(diff.toFloat() / mins) else "N/A"
            BenchmarkLogger.log("BATTERY_DIFF END: $nowPct% | consumed $diff% in ${mins}min | rate $rate%/min")
            batteryStartPct = -1
            refreshBattery()
        }

    }
}
