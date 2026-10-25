package com.phoneai.app

/** Bounded defaults for the explicitly opt-in Gemini Live voice mode. */
internal object GeminiLivePolicy {
    const val MODEL = "models/gemini-3.8-live"
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val AUDIO_CHUNK_SAMPLES = 640 // 40 ms at 16 kHz
    const val SESSION_LIMIT_MS = 5 * 60 * 1000L
    const val TOKEN_NEW_SESSION_WINDOW_MS = 90 * 1000L
    const val TOKEN_EXPIRE_AFTER_MS = 7 * 60 * 1000L
    const val PLAYBACK_QUEUE_CAPACITY = 100
    /** Ends the call when nothing has been said by either side for this long. */
    const val SILENCE_LIMIT_MS = 2 * 60 * 1000L
    /** Mic RMS above this counts as speech for the silence timer. */
    const val SPEECH_RMS = 0.02f

    fun silenceExceeded(nowMs: Long, lastActivityMs: Long): Boolean = nowMs - lastActivityMs >= SILENCE_LIMIT_MS

    /** Maps the user's setting to the Live API's activity-detection sensitivity enums. */
    fun activityDetection(sensitivity: String): Pair<String, String> = when (sensitivity) {
        "high" -> "START_SENSITIVITY_HIGH" to "END_SENSITIVITY_HIGH"
        "low" -> "START_SENSITIVITY_LOW" to "END_SENSITIVITY_LOW"
        else -> "START_SENSITIVITY_UNSPECIFIED" to "END_SENSITIVITY_UNSPECIFIED"
    }

    const val QUOTA_WARNING =
        "Gemini Live streams microphone audio to Google and consumes Gemini API quota. " +
        "Audio-token usage accumulates with conversation context; account limits and pricing vary. " +
        "Orion automatically ends each session after five minutes. Continue only with your own Gemini key."
}
