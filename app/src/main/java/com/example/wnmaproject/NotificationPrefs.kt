package com.example.trekmesh

import android.content.Context

enum class NotificationFilter { ALL, SOS_ONLY, INFO_ONLY, DISABLED }

object NotificationPrefs {
    private const val PREFS_NAME = "trekmesh_prefs"
    private const val KEY_FILTER = "notification_filter"

    fun getFilter(context: Context): NotificationFilter {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FILTER, NotificationFilter.ALL.name)
        return runCatching { NotificationFilter.valueOf(raw!!) }.getOrDefault(NotificationFilter.ALL)
    }

    fun saveFilter(context: Context, filter: NotificationFilter) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FILTER, filter.name).apply()
    }
}
