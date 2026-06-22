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

class ReceivedMessagesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override fun onResume() {
        super.onResume()
        TrekMeshBus.resetUnread()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scroll = view.findViewById<ScrollView>(R.id.scroll_messages)
        val container = view.findViewById<LinearLayout>(R.id.container_messages)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrekMeshBus.messages.collect { messages ->
                    val now = System.currentTimeMillis()
                    val sixH = now - 6 * 60 * 60 * 1000L
                    val twentyFourH = now - 24 * 60 * 60 * 1000L
                    val received = messages
                        .filter { msg ->
                            if (msg.label == "Tu") return@filter false
                            if (msg.status !in listOf("RECEIVED", "ACKNOWLEDGED", "RESOLVED")) return@filter false
                            when {
                                msg.type == "BROADCAST" -> msg.timestamp > sixH
                                msg.type == "INFO" && msg.priority < 3 -> msg.timestamp > sixH
                                msg.type == "INFO" && msg.priority >= 3 -> msg.timestamp > twentyFourH
                                msg.type == "SOS" -> msg.timestamp > twentyFourH
                                else -> true
                            }
                        }
                        .sortedWith(
                            compareByDescending<ChatMessage> { if (it.type == "SOS") it.priority + 3 else it.priority }
                                .thenByDescending { it.ttl >= 6 } // "vicino a te" prima
                                .thenByDescending { it.timestamp }
                        )
                    val atBottom = !scroll.canScrollVertically(1)
                    container.removeAllViews()
                    received.forEach { container.addView(buildMessageCard(requireContext(), it)) }
                    if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
