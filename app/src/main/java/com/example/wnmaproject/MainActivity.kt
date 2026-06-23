package com.example.trekmesh

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var isHandlingSettingsTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupSafetyTimerUI()
        setupMeshStatusUI()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        lifecycleScope.launch {
            com.example.trekmesh.db.TrekMeshDatabase.getInstance(applicationContext)
                .messageDao().deleteExpired()
            if (MeshServicePrefs.isEnabled(this@MainActivity)) {
                startForegroundService(Intent(this@MainActivity, TrekMeshService::class.java))
            }
        }

        val isDebug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)

        viewPager.adapter = MessagesPagerAdapter(this, showLog = isDebug)

        // Build tabs manually so we can add Settings as a pseudo-tab
        val contentTabCount = if (isDebug) 3 else 2
        val settingsTabIndex = contentTabCount

        tabLayout.addTab(tabLayout.newTab().setText("Received"))
        tabLayout.addTab(tabLayout.newTab().setText("Sent"))
        if (isDebug) tabLayout.addTab(tabLayout.newTab().setText("Log"))
        tabLayout.addTab(
            tabLayout.newTab()
                .setIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_preferences))
                .setContentDescription("Settings")
        )

        // ViewPager → TabLayout
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!isHandlingSettingsTab) {
                    tabLayout.selectTab(tabLayout.getTabAt(position))
                }
            }
        })

        // TabLayout → ViewPager (Settings tab intercept)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isHandlingSettingsTab) return
                if (tab.position == settingsTabIndex) {
                    isHandlingSettingsTab = true
                    tabLayout.selectTab(tabLayout.getTabAt(viewPager.currentItem))
                    isHandlingSettingsTab = false
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                } else {
                    viewPager.currentItem = tab.position
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == settingsTabIndex) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
        })

        setupUnreadBadge(tabLayout)

        // SEND FAB
        findViewById<ExtendedFloatingActionButton>(R.id.fab_compose).setOnClickListener {
            startActivity(Intent(this, ComposeMessageActivity::class.java))
        }

        // SOS FAB
        val fabSos = findViewById<ExtendedFloatingActionButton>(R.id.fab_sos)
        fabSos.setOnClickListener { showSosConfirmDialog() }
        fabSos.setOnLongClickListener {
            showSafetyTimerDialog()
            true
        }

        // Debug reset FAB
        val fabReset = findViewById<FloatingActionButton>(R.id.fab_debug_reset)
        if (isDebug) {
            fabReset.visibility = View.VISIBLE
            fabReset.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("[DEBUG] Reset application")
                    .setMessage("All data (database, preferences) will be deleted and all permissions revoked. The app will close.")
                    .setPositiveButton("Reset") { _, _ ->
                        getSystemService(ActivityManager::class.java).clearApplicationUserData()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun setupUnreadBadge(tabLayout: TabLayout) {
        lifecycleScope.launch {
            TrekMeshBus.unreadCount.collect { count ->
                val badge = tabLayout.getTabAt(0)?.orCreateBadge
                if (count > 0) {
                    badge?.isVisible = true
                    badge?.number = count
                } else {
                    tabLayout.getTabAt(0)?.removeBadge()
                }
            }
        }
    }

    private fun setupMeshStatusUI() {
        lifecycleScope.launch {
            TrekMeshBus.peerCount.collect { count ->
                updateMeshStatusBar(count)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMeshStatusBar(TrekMeshBus.peerCount.value)
    }

    private fun updateMeshStatusBar(peerCount: Int) {
        val tv = findViewById<TextView>(R.id.tv_mesh_status)
        if (!MeshServicePrefs.isEnabled(this)) {
            tv.text = "⚙ Enable mesh from Settings to use the app"
            tv.setTextColor(0xFFFF9800.toInt())
        } else if (peerCount == 0) {
            tv.text = "● Mesh: no nodes"
            tv.setTextColor(0xFF888888.toInt())
        } else {
            tv.text = "● Mesh: $peerCount ${if (peerCount > 1) "nodes connected" else "node connected"}"
            tv.setTextColor(0xFF4CAF50.toInt())
        }
    }

    private fun setupSafetyTimerUI() {
        val card = findViewById<MaterialCardView>(R.id.card_safety_timer)
        val text = findViewById<TextView>(R.id.text_safety_timer)
        val btnOk = findViewById<Button>(R.id.btn_safety_ok)

        lifecycleScope.launch {
            TrekMeshBus.safetyTimer.collect { seconds ->
                if (seconds > 0) {
                    card.visibility = View.VISIBLE
                    val mins = seconds / 60
                    val secs = seconds % 60
                    text.text = "Check-in in: %02d:%02d".format(mins, secs)
                } else {
                    card.visibility = View.GONE
                }
            }
        }

        btnOk.setOnClickListener {
            TrekMeshBus.triggerSafetyAction(SafetyTimerAction.STOP)
        }
    }

    private fun showSafetyTimerDialog() {
        val options = arrayOf("30 minutes", "1 hour", "2 hours")
        AlertDialog.Builder(this)
            .setTitle("Set Safety Timer")
            .setItems(options) { _, which ->
                val action = when (which) {
                    0 -> SafetyTimerAction.START_30M
                    1 -> SafetyTimerAction.START_1H
                    else -> SafetyTimerAction.START_2H
                }
                TrekMeshBus.triggerSafetyAction(action)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSosConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Quick SOS")
            .setMessage(
                "You are about to send:\n\n" +
                "\"A hiker is asking for help\"\n\n" +
                "Priority: HIGH (SOS 3)\n\n" +
                "This message cannot be modified or cancelled. Confirm?"
            )
            .setPositiveButton("Send SOS") { _, _ ->
                TrekMeshBus.sendMessage(
                    OutgoingMessage(
                        type     = "SOS",
                        priority = 3,
                        text     = "A hiker is asking for help"
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
