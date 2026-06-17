package com.example.trekmesh

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class RoleSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_selection)

        findViewById<Button>(R.id.button_select_hiker).setOnClickListener {
            selectRole(UserRole.HIKER)
        }

        findViewById<Button>(R.id.button_select_rifugio).setOnClickListener {
            selectRole(UserRole.RIFUGIO)
        }
    }

    private fun selectRole(role: UserRole) {
        UserRolePrefs.saveRole(this, role)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
