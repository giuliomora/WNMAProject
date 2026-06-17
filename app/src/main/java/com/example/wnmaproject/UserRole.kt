package com.example.trekmesh

import android.content.Context

enum class UserRole { HIKER, RIFUGIO }

object UserRolePrefs {
    private const val PREFS_NAME = "trekmesh_prefs"
    private const val KEY_ROLE = "user_role"

    fun getRole(context: Context): UserRole? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, null) ?: return null
        return runCatching { UserRole.valueOf(raw) }.getOrNull()
    }

    fun saveRole(context: Context, role: UserRole) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role.name).apply()
    }
}
