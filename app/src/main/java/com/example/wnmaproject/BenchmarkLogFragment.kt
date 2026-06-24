package com.example.trekmesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class BenchmarkLogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_benchmark_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvLog  = view.findViewById<TextView>(R.id.tv_benchlog_text)
        val scroll = view.findViewById<ScrollView>(R.id.scroll_benchlog)
        val tvPath = view.findViewById<TextView>(R.id.tv_benchlog_path)
        val btnClear  = view.findViewById<Button>(R.id.btn_benchlog_clear)
        val btnExport = view.findViewById<Button>(R.id.btn_benchlog_export)

        val logFile = BenchmarkLogger.logFile(requireContext())
        tvPath.text = logFile?.absolutePath ?: "file logging unavailable"

        // Live log
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.benchmarkLog.collect { lines ->
                tvLog.text = lines.joinToString("\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        btnClear.setOnClickListener {
            TrekMeshBus.clearBenchLog()
            logFile?.writeText("")
        }

        btnExport.setOnClickListener {
            val path = logFile?.absolutePath ?: "unknown"
            Toast.makeText(requireContext(), "Log saved at:\n$path", Toast.LENGTH_LONG).show()
        }
    }
}
