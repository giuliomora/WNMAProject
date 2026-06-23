package com.example.trekmesh

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MessagesPagerAdapter(activity: FragmentActivity, private val showLog: Boolean) :
    FragmentStateAdapter(activity) {

    // Release: Received, Sent, Settings           (3 tabs)
    // Debug:   Received, Sent, Log, Bench, Settings (5 tabs)
    override fun getItemCount() = if (showLog) 5 else 3

    override fun createFragment(position: Int): Fragment = when {
        position == 0              -> ReceivedMessagesFragment()
        position == 1              -> SentMessagesFragment()
        showLog && position == 2   -> LogFragment()
        showLog && position == 3   -> BenchmarkFragment()
        else                       -> SettingsFragment()
    }
}
