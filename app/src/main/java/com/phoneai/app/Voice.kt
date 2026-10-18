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

/** Speech in (SpeechRecognizer) and speech out (TextToSpeech). */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) callbacks.remove(utteranceId)?.invoke()
                    }
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) callbacks.remove(utteranceId)?.invoke()
                    }
                })
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady) {
            onDone?.invoke()
            return
        }
        val id = "u" + System.nanoTime()
        if (onDone != null) callbacks[id] = onDone
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stopSpeaking() {
        callbacks.clear()
        tts?.stop()
    }

    /** Calls cb once nothing is being spoken (or after a timeout). Runs on the main thread. */
    fun whenIdle(cb: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                tries++
                if (tts?.isSpeaking == true && tries < 80) h.postDelayed(this, 250) else cb()
            }
        }
        h.postDelayed(r, 400)
    }

    /** Must be called on the main thread. onText gets null when nothing was heard or on error. */
    fun listen(onText: (String?) -> Unit, onPartial: (String) -> Unit) {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onText(null)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                cleanup()
                onText(null)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                cleanup()
                onText(t)
            }
            override fun onPartialResults(partialResults: Bundle?) {
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
        try {
            rec?.cancel()
        } catch (e: Exception) {
        }
        cleanup()
    }

    fun shutdown() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
