package com.example.trekmesh

import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BenchmarkFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_benchmark, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvLog    = view.findViewById<TextView>(R.id.tv_bench_log)
        val tvPeers  = view.findViewById<TextView>(R.id.tv_bench_peers)
        val tvMode   = view.findViewById<TextView>(R.id.tv_bench_mode)
        val tvBatt   = view.findViewById<TextView>(R.id.tv_battery)
        val scroll   = view.findViewById<ScrollView>(R.id.scroll_bench_log)

        // Live battery display
        val bm = requireContext().getSystemService(BatteryManager::class.java)
        fun refreshBattery() {
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            tvBatt.text = "🔋 $pct%"
        }
        refreshBattery()

        // Peer count
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.peerCount.collect { count ->
                tvPeers.text = "● Peers: $count"
                tvPeers.setTextColor(if (count > 0) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
            }
        }

        // Benchmark log
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.benchmarkLog.collect { lines ->
                tvLog.text = lines.joinToString("\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // Scan mode hint (derived from Log events — just show last SCAN_START line)
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.benchmarkLog.collect { lines ->
                val lastScan = lines.lastOrNull { it.contains("SCAN_START") }
                tvMode.text = when {
                    lastScan?.contains("HIGH_POWER") == true -> "Mode: HIGH POWER (BT+WiFi)"
                    lastScan?.contains("LOW_POWER")  == true -> "Mode: LOW POWER (BT only)"
                    else -> "Mode: —"
                }
            }
        }

        // Packet loss test
        view.findViewById<Button>(R.id.btn_packet_loss).setOnClickListener {
            refreshBattery()
            TrekMeshBus.triggerBenchPing(50)
        }

        // Battery snapshot
        view.findViewById<Button>(R.id.btn_battery_snap).setOnClickListener {
            refreshBattery()
            BenchmarkLogger.logBattery(requireContext())
        }

        // Clear log
        view.findViewById<Button>(R.id.btn_clear_bench).setOnClickListener {
            TrekMeshBus.clearBenchLog()
        }
    }
}
