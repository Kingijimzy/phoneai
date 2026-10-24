package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Speech in (SpeechRecognizer) and speech out (TextToSpeech). */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    @Volatile private var listeningOnDevice = false
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pending = AtomicInteger(0)

    @Volatile
    private var gen = 0

    /** true when Orion starts talking, false when it finishes. Runs on the main thread. */
    @Volatile
    var onSpeaking: ((Boolean) -> Unit)? = null

    /** Microphone loudness 0..1 while listening. Runs on the main thread. */
    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    /** Fires for every spoken word (used to move the avatar's mouth). Runs on the main thread. */
    @Volatile
    var onWord: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                refreshVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        main.post { onWord?.invoke() }
                    }

                    override fun onDone(utteranceId: String?) {
                        finished(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finished(utteranceId)
                    }
                })
            }
        }
    }

    /** Voices of the phone's language that are installed, best first. */
    fun voiceList(): List<android.speech.tts.Voice> {
        val lang = Locale.getDefault().language
        return try {
            (tts?.voices ?: emptySet<android.speech.tts.Voice>())
                .filter { it.locale.language == lang && !it.features.contains("notInstalled") }
                .sortedWith(compareBy<android.speech.tts.Voice> { it.features.contains("networkTts") }.thenByDescending { it.quality }.thenBy { it.latency })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Applies the voice picked in settings, or the best one the phone has. */
    fun refreshVoice() {
        val engine = tts ?: return
        try {
            val all = voiceList()
            val wanted = Prefs.voiceName(ctx)
            val offline = all.firstOrNull { !it.features.contains("networkTts") }
            val pick = all.firstOrNull { it.name == wanted && !it.features.contains("networkTts") }
                ?: offline
                ?: all.firstOrNull { it.name == wanted }
            if (pick != null) engine.voice = pick
        } catch (e: Exception) {
        }
    }

    private fun finished(id: String?) {
        if (id != null) callbacks.remove(id)?.invoke()
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            main.post { onSpeaking?.invoke(false) }
        }
    }

    private fun clean(s: String): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("https?://\\S+"), "link").trim()

    /** Split long replies on sentence boundaries, preserving conversational flow. */
    private fun splitForSpeech(text: String, limit: Int = 420): List<String> {
        val sentences = text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (sentence in sentences) {
            var remaining = sentence
            while (remaining.length > limit) {
                val window = remaining.substring(0, limit)
                val cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(" "))
                val splitAt = if (cut > limit / 2) cut else limit
                out.add(remaining.substring(0, splitAt).trim())
                remaining = remaining.substring(splitAt).trim()
            }
            if (remaining.isNotEmpty()) out.add(remaining)
        }
        return out
    }

    fun isSpeaking(): Boolean = pending.get() > 0 || tts?.isSpeaking == true

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = clean(text)
        if (!ttsReady || t.isEmpty()) {
            onDone?.invoke()
            return
        }
        val engine = tts ?: return
        // Keep a stable rate and pitch across each response. Changing pitch sentence by
        // sentence sounds synthetic; chunk only when needed to respect Android TTS limits.
        val chunks = splitForSpeech(t)
        if (chunks.isEmpty()) {
            onDone?.invoke()
            return
        }
        engine.setSpeechRate(Prefs.rate(ctx).coerceIn(0.75f, 1.25f))
        engine.setPitch(if (Prefs.deepVoice(ctx)) 0.92f else 1.0f)
        for ((idx, part) in chunks.withIndex()) {
            if (idx > 0) {
                val previous = chunks[idx - 1]
                val pauseMs = if (previous.endsWith("?") || previous.endsWith("!")) 210L
                    else if (previous.endsWith(".")) 170L else 90L
                engine.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, "pause_" + System.nanoTime())
            }
            val id = "u" + System.nanoTime()
            if (onDone != null && idx == chunks.lastIndex) callbacks[id] = onDone
            if (pending.getAndIncrement() == 0) main.post { onSpeaking?.invoke(true) }
            val res = engine.speak(part, TextToSpeech.QUEUE_ADD, null, id)
            if (res != TextToSpeech.SUCCESS) finished(id)
        }
    }

    fun stopSpeaking() {
        callbacks.clear()
        tts?.stop()
        if (pending.getAndSet(0) > 0) main.post { onSpeaking?.invoke(false) }
    }

    /** Calls cb once nothing is being spoken (or after a timeout). Runs on the main thread. */
    fun whenIdle(cb: () -> Unit) {
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                if (isSpeaking() && tries < 120) main.postDelayed(this, 250) else cb()
            }
        }
        main.postDelayed(r, 400)
    }

    /**
     * Must be called on the main thread. onText gets null when nothing was heard or on error.
     * onProblem gets a readable message for real problems (not for plain silence).
     */
    fun listen(
        onText: (String?) -> Unit,
        onPartial: (String) -> Unit,
        onProblem: ((String) -> Unit)? = null
    ) {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onProblem?.invoke("No speech recognition on this phone. Install or enable Google voice typing.")
            onText(null)
            return
        }
        val my = ++gen
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Exception) { false }
        // Track the recognizer that was actually created, not just whether Android
        // advertised on-device support. Creation can fail and fall back to the service.
        var usingOnDevice = false
        val r = try {
            if (onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { usingOnDevice = true }
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (_: Exception) {
            usingOnDevice = false
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        listeningOnDevice = usingOnDevice
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (my != gen) return
                onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (my == gen) onLevel?.invoke(0f)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (my != gen) return
                cleanup()
                onLevel?.invoke(0f)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing. Open Orion AI > Allow microphone."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> if (usingOnDevice) {
                        "On-device speech recognition failed. Check that an offline speech model is installed, then try again."
                    } else {
                        "Speech service needs internet."
                    }
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone is busy or unavailable."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again."
                    else -> null
                }
                if (msg != null) onProblem?.invoke(msg)
                onText(null)
            }

            override fun onResults(results: Bundle?) {
                if (my != gen) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                cleanup()
                onLevel?.invoke(0f)
                onText(t)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (my != gen) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) onPartial(t)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        // Hint to the installed recognizer to prefer an offline model when it has one.
        // Android may ignore this extra; the service can still fall back to a network recognizer.
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Android's default end-of-speech silence can feel like a timeout in a live call.
        // Shorten turn detection without making it so aggressive that normal pauses cut users off.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
        try {
            r.startListening(i)
        } catch (e: SecurityException) {
            if (my == gen) {
                cleanup()
                onLevel?.invoke(0f)
                onProblem?.invoke("Microphone permission is missing. Open Orion AI and allow microphone access.")
                onText(null)
            }
        } catch (e: RuntimeException) {
            if (my == gen) {
                cleanup()
                onLevel?.invoke(0f)
                onProblem?.invoke("Speech recognition could not start. Check the speech service and try again.")
                onText(null)
            }
        }
    }

    private fun cleanup() {
        try {
            rec?.destroy()
        } catch (e: Exception) {
        }
        rec = null
    }

    fun stopListening() {
        gen++
        listeningOnDevice = false
        try {
            rec?.cancel()
        } catch (e: Exception) {
        }
        cleanup()
        main.post { onLevel?.invoke(0f) }
    }

    fun shutdown() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
