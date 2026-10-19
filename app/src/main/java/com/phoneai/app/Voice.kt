package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency })
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
            val pick = all.firstOrNull { it.name == wanted } ?: all.firstOrNull()
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

    fun isSpeaking(): Boolean = pending.get() > 0 || tts?.isSpeaking == true

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = clean(text)
        if (!ttsReady || t.isEmpty()) {
            onDone?.invoke()
            return
        }
        val engine = tts ?: return
        // Speak sentence by sentence, with a little rise for questions and energy for excitement.
        val parts = t.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val baseRate = Prefs.rate(ctx).coerceIn(0.5f, 2.0f)
        val basePitch = if (Prefs.deepVoice(ctx)) 0.8f else 1.0f
        for ((idx, part) in parts.withIndex()) {
            var rate = baseRate
            var pitch = basePitch
            if (part.endsWith("?")) pitch += 0.1f
            if (part.endsWith("!")) {
                rate *= 1.06f
                pitch += 0.05f
            }
            if (idx > 0 && idx % 2 == 1) pitch -= 0.03f
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val id = "u" + System.nanoTime()
            if (onDone != null && idx == parts.size - 1) callbacks[id] = onDone
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
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
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
                    SpeechRecognizer.ERROR_SERVER -> "Speech service needs internet."
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
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        r.startListening(i)
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
