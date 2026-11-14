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

    fun onRuntimeStatus(label: String, mode: OrbView.Mode, taskRunning: Boolean = false) {
        state.setStatus(label)
        state.orbMode.value = mode
        state.taskRunning.value = taskRunning
        if (mode == OrbView.Mode.IDLE && !taskRunning && state.hasAssistantStream()) {
            // Preserve partial text but release the stream identity after a stop/completion.
            state.resetAssistantStream()
        }
    }

    fun onAudioLevel(level: Float) {
        state.orbMicLevel.value = level.coerceIn(0f, 1f)
    }

    fun onAudioAmplitude(amplitude: Float) {
        state.orbSpeechAmplitude.value = amplitude.coerceIn(0f, 1f)
    }

    fun onSpeechWord() {
        state.orbWordPulse.value = 1f
    }

    fun setAttachments(attachments: List<OrionChatAttachmentStore.Attachment>) {
        state.attachments.value = attachments
    }

    fun setCompanionLook(look: Int) {
        state.companionLook.value = look.coerceIn(0, 2)
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
        // Session changes must not inherit a previous task's spinner or approval state.
        state.taskRunning.value = false
        state.orbMode.value = OrbView.Mode.IDLE
        state.setStatus("READY")
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
        state.taskRunning.value = false
        state.orbMode.value = OrbView.Mode.IDLE
        state.setStatus("READY")
    }
}
