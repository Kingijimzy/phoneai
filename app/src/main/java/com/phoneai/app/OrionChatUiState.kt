package com.phoneai.app

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * Observable state for the full-screen chat surface.
 *
 * Activity lifecycle, Android permissions and service orchestration stay in MainChatActivity;
 * this object owns only values rendered by OrionComposeChatScreen. The existing callback bridge
 * is preserved so moving state out does not silently change execution behavior.
 */
internal class OrionChatUiState {
    val lines = mutableStateListOf<OrionChatLine>()
    val providerLabel = mutableStateOf("Choose provider")
    val modelLabel = mutableStateOf("Choose model")
    val providerReady = mutableStateOf(false)
    val status = mutableStateOf("READY")
    val chatTitle = mutableStateOf("Orion")
    val commandMode = mutableStateOf(false)
    val selectedImages = mutableStateOf<List<Uri>>(emptyList())

    private var streamingMessageId: Long? = null
    private val streamingBuffer = StringBuilder()

    fun setProvider(label: String, ready: Boolean) {
        providerLabel.value = label
        providerReady.value = ready
    }

    fun setModel(label: String) {
        modelLabel.value = label
    }

    fun setStatus(value: String) {
        status.value = value
    }

    fun setTitle(value: String) {
        chatTitle.value = value.take(48).ifBlank { "Orion" }
    }

    fun setCommandMode(enabled: Boolean) {
        commandMode.value = enabled
    }

    fun setSelectedImages(uris: List<Uri>) {
        selectedImages.value = uris.take(4)
    }

    fun append(line: OrionChatLine) {
        lines.add(line)
    }

    /**
     * Owns the identity and accumulated text of the current assistant stream.
     * Deltas update one stable row instead of creating a new message per callback.
     */
    fun appendAssistantDelta(delta: String): OrionChatLine? {
        if (delta.isEmpty()) return null
        streamingBuffer.append(delta)
        val id = streamingMessageId ?: System.nanoTime().also { streamingMessageId = it }
        val line = OrionChatLine(id, "ai", streamingBuffer.toString())
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) lines[index] = line else lines.add(line)
        return line
    }

    /** Reconcile the final service transcript with the streamed row, then release stream state. */
    fun completeAssistantStream(finalText: String): OrionChatLine {
        val id = streamingMessageId ?: System.nanoTime()
        val line = OrionChatLine(id, "ai", finalText)
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) lines[index] = line else lines.add(line)
        resetAssistantStream()
        return line
    }

    fun hasAssistantStream(): Boolean = streamingMessageId != null

    fun resetAssistantStream() {
        streamingMessageId = null
        streamingBuffer.setLength(0)
    }

    fun clearTranscript() {
        lines.clear()
        resetAssistantStream()
    }
}

/** Runtime state, not mutable status copy, determines whether the response control is Stop. */
internal fun isOrionChatBusy(mode: OrbView.Mode): Boolean = when (mode) {
    OrbView.Mode.THINKING, OrbView.Mode.SPEAKING, OrbView.Mode.ACTING, OrbView.Mode.WAITING_APPROVAL -> true
    OrbView.Mode.IDLE, OrbView.Mode.LISTENING, OrbView.Mode.ERROR -> false
}
