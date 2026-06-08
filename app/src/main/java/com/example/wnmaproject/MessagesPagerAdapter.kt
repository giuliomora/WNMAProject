package com.example.trekmesh

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MessagesPagerAdapter(activity: FragmentActivity, private val showLog: Boolean) :
    FragmentStateAdapter(activity) {

    override fun getItemCount() = if (showLog) 3 else 2

    override fun createFragment(position: Int): Fragment = when (position) {
        0    -> ReceivedMessagesFragment()
        1    -> SentMessagesFragment()
        else -> LogFragment()
    }
}
