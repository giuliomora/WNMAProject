package com.example.trekmesh

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class BenchmarkLogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_benchmark_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvLog  = view.findViewById<TextView>(R.id.tv_benchlog_text)
        val scroll = view.findViewById<ScrollView>(R.id.scroll_benchlog)
        val tvPath = view.findViewById<TextView>(R.id.tv_benchlog_path)

        val logFile = BenchmarkLogger.logFile(requireContext())
        tvPath.text = logFile?.absolutePath ?: "file logging unavailable"

        // Live log — auto-scroll to bottom on update
        viewLifecycleOwner.lifecycleScope.launch {
            TrekMeshBus.benchmarkLog.collect { lines ->
                tvLog.text = lines.joinToString("\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // Share via system share sheet (email, Drive, etc.)
        view.findViewById<Button>(R.id.btn_benchlog_share).setOnClickListener {
            val file = logFile
            if (file == null || !file.exists() || file.length() == 0L) {
                Toast.makeText(requireContext(), "Log file is empty or unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TrekMesh Benchmark Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share benchmark log"))
        }

        // Clear in-memory log + file
        view.findViewById<Button>(R.id.btn_benchlog_clear).setOnClickListener {
            TrekMeshBus.clearBenchLog()
            try { logFile?.writeText("") } catch (_: Exception) {}
        }
    }
}
