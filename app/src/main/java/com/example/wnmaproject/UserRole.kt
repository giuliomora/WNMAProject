package com.example.trekmesh

import android.content.Context

enum class UserRole { HIKER, RIFUGIO }

object UserRolePrefs {
    private const val PREFS_NAME = "trekmesh_prefs"
    private const val KEY_ROLE = "user_role"
    private const val PREFS_ENDPOINT = "trekmesh_node"
    private const val KEY_RIFUGIO_NAME = "rifugio_name"

    fun getStoredRifugioName(context: Context): String? =
        context.getSharedPreferences(PREFS_ENDPOINT, Context.MODE_PRIVATE)
            .getString(KEY_RIFUGIO_NAME, null)

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
