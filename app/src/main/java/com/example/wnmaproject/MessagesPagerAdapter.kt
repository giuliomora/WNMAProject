package com.example.trekmesh

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MessagesPagerAdapter(activity: FragmentActivity, private val showLog: Boolean) :
    FragmentStateAdapter(activity) {

    // Received, Sent, [Log,] Settings
    override fun getItemCount() = if (showLog) 4 else 3

    override fun createFragment(position: Int): Fragment = when {
        position == 0 -> ReceivedMessagesFragment()
        position == 1 -> SentMessagesFragment()
        showLog && position == 2 -> LogFragment()
        else -> SettingsFragment()
    }
}
