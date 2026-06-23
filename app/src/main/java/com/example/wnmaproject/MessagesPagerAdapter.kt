package com.example.trekmesh

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MessagesPagerAdapter(activity: FragmentActivity, private val showLog: Boolean) :
    FragmentStateAdapter(activity) {

    private val showBench = BuildConfig.BENCHMARK_MODE

    // Release/Debug: Received, Sent, [Log,] Settings
    // Benchmark:     Received, Sent, [Log,] Bench, Settings
    override fun getItemCount(): Int {
        var count = 2 // Received + Sent
        if (showLog) count++ // Log
        if (showBench) count++ // Bench
        count++ // Settings
        return count
    }

    override fun createFragment(position: Int): Fragment {
        var idx = position
        if (idx == 0) return ReceivedMessagesFragment()
        idx--
        if (idx == 0) return SentMessagesFragment()
        idx--
        if (showLog) {
            if (idx == 0) return LogFragment()
            idx--
        }
        if (showBench) {
            if (idx == 0) return BenchmarkFragment()
            idx--
        }
        return SettingsFragment()
    }
}
