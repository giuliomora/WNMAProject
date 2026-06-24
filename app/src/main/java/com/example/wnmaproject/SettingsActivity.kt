package com.example.trekmesh

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        setupMeshServiceSection()
        setupRoleSection()
        setupNodeNameSection()
        setupNotificationSection()
    }

    private fun setupMeshServiceSection() {
        val switch = findViewById<SwitchCompat>(R.id.switch_mesh_service)
        val statusText = findViewById<TextView>(R.id.tv_service_status)
        val enabled = MeshServicePrefs.isEnabled(this)
        switch.isChecked = enabled
        updateServiceStatusUI(statusText, enabled)

        switch.setOnCheckedChangeListener { _, isChecked ->
            MeshServicePrefs.setEnabled(this, isChecked)
            updateServiceStatusUI(statusText, isChecked)
            // Il servizio rimane sempre attivo per il monitoraggio passivo BLE.
            // onStartCommand rileva lo stato e avvia/ferma Nearby di conseguenza.
            startForegroundService(android.content.Intent(this, TrekMeshService::class.java))
        }
    }

    private fun updateServiceStatusUI(tv: TextView, enabled: Boolean) {
        if (enabled) {
            tv.text = "Mesh active"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "Mesh disabled"
            tv.setTextColor(0xFF888888.toInt())
        }
    }

    private fun setupRoleSection() {
        val currentRole = UserRolePrefs.getRole(this) ?: UserRole.HIKER
        updateRoleUI(currentRole)

        findViewById<Button>(R.id.btn_change_role).setOnClickListener {
            val newRole = if (currentRole == UserRole.HIKER) UserRole.RIFUGIO else UserRole.HIKER
            val newRoleLabel = if (newRole == UserRole.RIFUGIO) "Rifugio 🏔️" else "Hiker 🥾"
            AlertDialog.Builder(this)
                .setTitle("Change role")
                .setMessage("You are about to switch to $newRoleLabel.\n\nThe mesh service will restart and your network identifier will change. Continue?")
                .setPositiveButton("Change") { _, _ ->
                    UserRolePrefs.saveRole(this, newRole)
                    updateRoleUI(newRole)
                    setupNodeNameSection()
                    restartMeshService()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateRoleUI(role: UserRole) {
        val icon = if (role == UserRole.RIFUGIO) "🏔️" else "🥾"
        val label = if (role == UserRole.RIFUGIO) "Rifugio" else "Hiker"
        val color = if (role == UserRole.RIFUGIO) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
        findViewById<TextView>(R.id.tv_role_icon).text = icon
        findViewById<TextView>(R.id.tv_current_role).apply {
            text = label
            setTextColor(color)
        }
        // Aggiorna il testo del pulsante in base al ruolo opposto
        val nextLabel = if (role == UserRole.RIFUGIO) "→ Hiker" else "→ Rifugio"
        findViewById<Button>(R.id.btn_change_role).text = nextLabel
    }

    private fun restartMeshService() {
        startForegroundService(android.content.Intent(this, TrekMeshService::class.java))
    }

    private fun setupNodeNameSection() {
        val tvName = findViewById<TextView>(R.id.tv_node_name)
        val btnEdit = findViewById<Button>(R.id.btn_edit_name)
        val role = UserRolePrefs.getRole(this) ?: UserRole.HIKER

        if (role == UserRole.RIFUGIO) {
            val name = UserRolePrefs.getStoredRifugioName(this) ?: "Rifugio"
            tvName.text = name
            btnEdit.visibility = android.view.View.VISIBLE
            btnEdit.setOnClickListener { showEditNameDialog(tvName) }
        } else {
            val randomName = getSharedPreferences("trekmesh_node", MODE_PRIVATE)
                .getString("hiker_name", null) ?: "Hiker (generated on first launch)"
            tvName.text = randomName
            tvName.setTextColor(0xFF888888.toInt())
        }
    }

    private fun showEditNameDialog(tvName: TextView) {
        val input = EditText(this).apply {
            setText(tvName.text)
            hint = "Nome rifugio"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim().ifBlank { return@setPositiveButton }
                getSharedPreferences("trekmesh_node", MODE_PRIVATE)
                    .edit().putString("rifugio_name", newName).apply()
                tvName.text = newName
                restartMeshService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupNotificationSection() {
        val rg = findViewById<RadioGroup>(R.id.rg_notification_filter)
        val current = NotificationPrefs.getFilter(this)

        val checkedId = when (current) {
            NotificationFilter.ALL       -> R.id.rb_notif_all
            NotificationFilter.SOS_ONLY  -> R.id.rb_notif_sos_only
            NotificationFilter.INFO_ONLY -> R.id.rb_notif_info_only
            NotificationFilter.DISABLED  -> R.id.rb_notif_disabled
        }
        rg.check(checkedId)

        rg.setOnCheckedChangeListener { _, id ->
            val filter = when (id) {
                R.id.rb_notif_all       -> NotificationFilter.ALL
                R.id.rb_notif_sos_only  -> NotificationFilter.SOS_ONLY
                R.id.rb_notif_info_only -> NotificationFilter.INFO_ONLY
                R.id.rb_notif_disabled  -> NotificationFilter.DISABLED
                else                    -> NotificationFilter.ALL
            }
            NotificationPrefs.saveFilter(this, filter)
        }
    }
}
