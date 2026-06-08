package com.example.trekmesh

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class OnboardingActivity : AppCompatActivity() {

    private lateinit var buttonGrant: Button
    private lateinit var buttonSettings: Button
    private lateinit var textDenied: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            proceedToMain()
        } else {
            showDeniedState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasAllPermissions()) {
            proceedToMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        buttonGrant = findViewById(R.id.button_grant_permissions)
        buttonSettings = findViewById(R.id.button_open_settings)
        textDenied = findViewById(R.id.text_permission_denied)

        buttonGrant.setOnClickListener {
            permissionLauncher.launch(buildPermissionsList().toTypedArray())
        }

        buttonSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Quando l'utente torna dalle impostazioni, ricontrolla
        if (hasAllPermissions()) proceedToMain()
    }

    private fun proceedToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showDeniedState() {
        textDenied.visibility = android.view.View.VISIBLE
        buttonSettings.visibility = android.view.View.VISIBLE
        buttonGrant.text = "Riprova"
    }

    private fun hasAllPermissions(): Boolean =
        buildPermissionsList().all {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
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
}
