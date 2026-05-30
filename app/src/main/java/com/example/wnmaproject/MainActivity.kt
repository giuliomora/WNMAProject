package com.example.trekmesh

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var startButton: Button
    private lateinit var logConsole: TextView
    private lateinit var editMessage: EditText
    private lateinit var sendButton: Button
    private var startRequested = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w("MainActivity", "Permissions denied: $denied")
            appendLog("Permessi negati: $denied")
            startRequested = false
        } else if (startRequested) {
            Log.i("MainActivity", "All permissions granted, starting TrekMeshService")
            appendLog("Permessi OK, avvio TrekMeshService...")
            startTrekMeshService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startButton = findViewById(R.id.button_start_trekmesh)
        logConsole = findViewById(R.id.text_log_console)
        editMessage = findViewById(R.id.edit_message)
        sendButton = findViewById(R.id.button_send)

        sendButton.setOnClickListener { sendMessage() }
        editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        startButton.setOnClickListener {
            startRequested = true
            if (hasAllNearbyPermissions()) {
                appendLog("Permessi già concessi, avvio TrekMeshService...")
                startTrekMeshService()
            } else {
                appendLog("Richiesta permessi...")
                requestNearbyRuntimePermissions()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TrekMeshBus.logs.collect { lines ->
                        logConsole.text = lines.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val text = editMessage.text.toString().trim()
        if (text.isNotEmpty()) {
            TrekMeshBus.sendMessage(text)
            editMessage.text.clear()
        }
    }

    private fun appendLog(message: String) {
        TrekMeshBus.emitLog(message)
    }

    private fun hasAllNearbyPermissions(): Boolean {
        return buildPermissionsList().all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNearbyRuntimePermissions() {
        val missing = buildPermissionsList().filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun buildPermissionsList(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
        }

        return permissions
    }

    private fun startTrekMeshService() {
        val serviceIntent = Intent(this, TrekMeshService::class.java)
        // minSdk=26 è sempre >= Android 8.0, quindi startForegroundService è sempre disponibile
        startForegroundService(serviceIntent)
    }
}