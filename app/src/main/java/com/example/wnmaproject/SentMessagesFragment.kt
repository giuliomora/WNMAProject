package com.example.trekmesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SentMessagesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scroll = view.findViewById<NestedScrollView>(R.id.scroll_messages)
        val container = view.findViewById<LinearLayout>(R.id.container_messages)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrekMeshBus.messages.collect { messages ->
                    val now = System.currentTimeMillis()
                    val sixH = now - 6 * 60 * 60 * 1000L
                    val twentyFourH = now - 24 * 60 * 60 * 1000L
                    val sent = messages
                        .filter { msg ->
                            if (msg.status !in listOf("PENDING", "DELIVERED", "ACKNOWLEDGED", "RESOLVED")) return@filter false
                            when {
                                msg.type == "BROADCAST" -> msg.timestamp > sixH
                                msg.type == "INFO" && msg.priority < 3 -> msg.timestamp > sixH
                                msg.type == "INFO" && msg.priority >= 3 -> msg.timestamp > twentyFourH
                                msg.type == "SOS" -> msg.timestamp > twentyFourH
                                else -> true
                            }
                        }
                        .sortedByDescending { it.timestamp }
                    val atBottom = !scroll.canScrollVertically(1)
                    container.removeAllViews()
                    sent.forEach { container.addView(buildMessageCard(requireContext(), it)) }
                    if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
