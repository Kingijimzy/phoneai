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

    fun clearTranscript() {
        lines.clear()
    }
}
