package com.example.trekmesh

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupSafetyTimerUI()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        startForegroundService(Intent(this, TrekMeshService::class.java))

        val isDebug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)

        viewPager.adapter = MessagesPagerAdapter(this, showLog = isDebug)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0    -> "Ricevuti"
                1    -> "Inviati"
                else -> "Log"
            }
        }.attach()

        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<FloatingActionButton>(R.id.fab_compose).setOnClickListener {
            ComposeMessageDialog().show(supportFragmentManager, "compose")
        }

        findViewById<FloatingActionButton>(R.id.fab_sos).setOnClickListener {
            showSosConfirmDialog()
        }

        findViewById<FloatingActionButton>(R.id.fab_sos).setOnLongClickListener {
            showSafetyTimerDialog()
            true
        }

        val fabReset = findViewById<FloatingActionButton>(R.id.fab_debug_reset)
        if (isDebug) {
            fabReset.visibility = View.VISIBLE
            fabReset.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("[DEBUG] Reset applicazione")
                    .setMessage("Verranno cancellati tutti i dati (database, preferenze) e revocati tutti i permessi. L'app si chiuderà.")
                    .setPositiveButton("Reset") { _, _ ->
                        getSystemService(ActivityManager::class.java).clearApplicationUserData()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
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
                    text.text = "Check-in tra: %02d:%02d".format(mins, secs)
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
        val options = arrayOf("30 minuti", "1 ora", "2 ore")
        AlertDialog.Builder(this)
            .setTitle("Imposta Timer di Sicurezza")
            .setItems(options) { _, which ->
                val action = when (which) {
                    0 -> SafetyTimerAction.START_30M
                    1 -> SafetyTimerAction.START_1H
                    else -> SafetyTimerAction.START_2H
                }
                TrekMeshBus.triggerSafetyAction(action)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSosConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Invio SOS rapido")
            .setMessage(
                "Stai per inviare:\n\n" +
                "\"Un escursionista sta chiedendo aiuto\"\n\n" +
                "Priorità: ALTA (SOS 3)\n\n" +
                "Il messaggio non potrà essere modificato o annullato. Confermi?"
            )
            .setPositiveButton("Invia SOS") { _, _ ->
                TrekMeshBus.sendMessage(
                    OutgoingMessage(
                        type     = "SOS",
                        priority = 3,
                        text     = "Un escursionista sta chiedendo aiuto"
                    )
                )
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
