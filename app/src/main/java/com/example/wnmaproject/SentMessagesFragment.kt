package com.example.trekmesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SentMessagesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scroll = view.findViewById<ScrollView>(R.id.scroll_messages)
        val container = view.findViewById<LinearLayout>(R.id.container_messages)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrekMeshBus.messages.collect { messages ->
                    val sent = messages
                        .filter { it.status == "PENDING" || it.status == "DELIVERED" }
                        .sortedByDescending { it.timestamp }
                    container.removeAllViews()
                    sent.forEach { container.addView(buildMessageCard(requireContext(), it)) }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
