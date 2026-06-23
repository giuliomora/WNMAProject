package com.example.trekmesh

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMeshServiceSection(view)
        setupRoleSection(view)
        setupNodeNameSection(view)
        setupNotificationSection(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            setupRoleSection(it)
            setupNodeNameSection(it)
        }
    }

    private fun setupMeshServiceSection(view: View) {
        val switch = view.findViewById<SwitchCompat>(R.id.switch_mesh_service)
        val statusText = view.findViewById<TextView>(R.id.tv_service_status)
        val enabled = MeshServicePrefs.isEnabled(requireContext())
        switch.isChecked = enabled
        updateServiceStatusUI(statusText, enabled)

        switch.setOnCheckedChangeListener { _, isChecked ->
            MeshServicePrefs.setEnabled(requireContext(), isChecked)
            updateServiceStatusUI(statusText, isChecked)
            requireContext().startForegroundService(Intent(requireContext(), TrekMeshService::class.java))
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

    private fun setupRoleSection(view: View) {
        val currentRole = UserRolePrefs.getRole(requireContext()) ?: UserRole.HIKER
        updateRoleUI(view, currentRole)

        view.findViewById<Button>(R.id.btn_change_role).setOnClickListener {
            val newRole = if (currentRole == UserRole.HIKER) UserRole.RIFUGIO else UserRole.HIKER
            val newRoleLabel = if (newRole == UserRole.RIFUGIO) "Mountain Hut 🏔️" else "Hiker 🥾"
            AlertDialog.Builder(requireContext())
                .setTitle("Change role")
                .setMessage("You are about to switch to $newRoleLabel.\n\nThe mesh service will restart and your network identifier will change. Continue?")
                .setPositiveButton("Change") { _, _ ->
                    UserRolePrefs.saveRole(requireContext(), newRole)
                    updateRoleUI(view, newRole)
                    setupNodeNameSection(view)
                    requireContext().startForegroundService(Intent(requireContext(), TrekMeshService::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateRoleUI(view: View, role: UserRole) {
        val icon = if (role == UserRole.RIFUGIO) "🏔️" else "🥾"
        val label = if (role == UserRole.RIFUGIO) "Mountain Hut" else "Hiker"
        val color = if (role == UserRole.RIFUGIO) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
        view.findViewById<TextView>(R.id.tv_role_icon).text = icon
        view.findViewById<TextView>(R.id.tv_current_role).apply {
            text = label
            setTextColor(color)
        }
        val nextLabel = if (role == UserRole.RIFUGIO) "→ Hiker" else "→ Mountain Hut"
        view.findViewById<Button>(R.id.btn_change_role).text = nextLabel
    }

    private fun setupNodeNameSection(view: View) {
        val tvName = view.findViewById<TextView>(R.id.tv_node_name)
        val btnEdit = view.findViewById<Button>(R.id.btn_edit_name)
        val role = UserRolePrefs.getRole(requireContext()) ?: UserRole.HIKER

        if (role == UserRole.RIFUGIO) {
            val name = UserRolePrefs.getStoredRifugioName(requireContext()) ?: "Mountain Hut"
            tvName.text = name
            tvName.setTextColor(0xFFFFFFFF.toInt())
            btnEdit.visibility = View.VISIBLE
            btnEdit.setOnClickListener { showEditNameDialog(tvName) }
        } else {
            val randomName = requireContext()
                .getSharedPreferences("trekmesh_node", android.content.Context.MODE_PRIVATE)
                .getString("hiker_name", null) ?: "Hiker (generated on first launch)"
            tvName.text = randomName
            tvName.setTextColor(0xFF888888.toInt())
            btnEdit.visibility = View.GONE
        }
    }

    private fun showEditNameDialog(tvName: TextView) {
        val input = EditText(requireContext()).apply {
            setText(tvName.text)
            hint = "Mountain hut name"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim().ifBlank { return@setPositiveButton }
                requireContext().getSharedPreferences("trekmesh_node", android.content.Context.MODE_PRIVATE)
                    .edit().putString("rifugio_name", newName).apply()
                tvName.text = newName
                requireContext().startForegroundService(Intent(requireContext(), TrekMeshService::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupNotificationSection(view: View) {
        val rg = view.findViewById<RadioGroup>(R.id.rg_notification_filter)
        val current = NotificationPrefs.getFilter(requireContext())

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
            NotificationPrefs.saveFilter(requireContext(), filter)
        }
    }
}
