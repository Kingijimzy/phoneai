package com.phoneai.app

/**
 * Projects service/runtime callbacks into the single observable chat state.
 *
 * Android Activity code remains responsible for permissions, navigation and platform widgets;
 * this coordinator owns the conversation-facing projection so UI state is not split across
 * callback handlers and Compose-local mutable values.
 */
internal class OrionChatCoordinator(private val state: OrionChatUiState) {

    fun onMessage(kind: String, text: String) {
        if (kind == "ai") {
            state.completeAssistantStream(text)
        } else {
            state.append(OrionChatLine(System.nanoTime(), kind, text))
        }
    }

    fun onAssistantDelta(delta: String) {
        state.appendAssistantDelta(delta)
    }

    fun onRuntimeStatus(label: String, mode: OrbView.Mode) {
        state.setStatus(label)
        state.orbMode.value = mode
    }

    fun onActivityEvents(events: List<OrionConversationRepository.ChatEvent>) {
        val projection = OrionActivityTimeline(maxEvents = 300)
        projection.replay(OrionActivityTimeline.fromPersisted(events))
        state.activityEvents.value = projection.compactForChat()
    }

    fun restoreTranscript(
        structured: List<OrionChatLine>,
        fallback: List<Pair<String, String>>,
        events: List<OrionConversationRepository.ChatEvent>
    ) {
        state.clearTranscript()
        if (structured.isNotEmpty()) {
            state.lines.addAll(structured)
        } else {
            fallback.forEachIndexed { index, pair ->
                state.append(OrionChatLine(System.nanoTime() + index, pair.first, pair.second))
            }
        }
        onActivityEvents(events)
    }

    fun clearSessionProjection() {
        state.clearTranscript()
        state.activityEvents.value = emptyList()
    }
}
