package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
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
import java.util.concurrent.Executors

/** Android speech recognition with optional sentence-streamed on-device neural TTS and Android TTS fallback. */
class Voice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitFinished = false
    private val queuedSpeech = java.util.concurrent.ConcurrentLinkedQueue<Pair<List<String>, (() -> Unit)?>>()
    private var rec: SpeechRecognizer? = null
    @Volatile private var listeningOnDevice = false
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pending = AtomicInteger(0)
    // Prevent duplicate or late platform callbacks from decrementing a newer speech turn.
    private val pendingIds = ConcurrentHashMap.newKeySet<String>()
    private val playbackGeneration = AtomicInteger(0)
    private val neuralWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Orion-Neural-TTS").apply { isDaemon = true }
    }
    @Volatile private var neuralTrack: AudioTrack? = null
    private var neuralTrackSampleRate = 0
    private var neuralFramesWritten = 0L
    // Accessed only by neuralWorker so native inference and release are serialized.
    private var neuralEngine: OfflineTts? = null
    private var neuralEnginePath: String? = null

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
    /** Real RMS amplitude from each generated Piper PCM block; zero is not synthesized into fallback audio. */
    @Volatile var onAudioAmplitude: ((Float) -> Unit)? = null

    /** Reports real playback/engine failures instead of silently dropping speech. */
    @Volatile var onProblem: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsInitFinished = true
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

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post {
                            onProblem?.invoke("Android speech engine failed (error $errorCode). Check Text-to-speech settings and installed voice data.")
                        }
                        finished(utteranceId)
                    }
                })
                main.post {
                    while (true) {
                        val queued = queuedSpeech.poll() ?: break
                        speakWithAndroidTts(queued.first, queued.second)
                    }
                }
            } else {
                main.post {
                    onProblem?.invoke("Android Text-to-speech could not initialize. Check Settings → System → Text-to-speech output and install a voice.")
                    while (true) {
                        val queued = queuedSpeech.poll() ?: break
                        queued.second?.invoke()
                    }
                }
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
                ?: if (Prefs.offlineVoiceOnly(ctx)) null else all.firstOrNull { it.name == wanted }
            if (pick != null) engine.voice = pick
        } catch (e: Exception) {
        }
    }

    private fun finished(id: String?) {
        // TTS may deliver both an error and a late completion. Only the first owns
        // this utterance's pending count; stopSpeaking clears the set before new turns.
        if (id == null || !pendingIds.remove(id)) return
        val callback = callbacks.remove(id)
        val remaining = pending.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            main.post { if (pending.get() == 0) onSpeaking?.invoke(false) }
        }
        callback?.let { cb -> main.post { cb() } }
    }

    private fun clean(s: String): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("https?://\\S+"), "link").trim()

    /** Split long replies on sentence boundaries, preserving conversational flow. */
    /** Keep split logic isolated so queue boundaries can be tested without a TTS engine. */
    private fun splitForSpeech(text: String, limit: Int = 420): List<String> =
        SpeechChunker.split(text, limit)

    fun isSpeaking(): Boolean = pending.get() > 0 || tts?.isSpeaking == true

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) {
            onDone?.invoke()
            return
        }
        val chunks = splitForSpeech(cleaned)
        if (chunks.isEmpty()) {
            onDone?.invoke()
            return
        }

        // The installed Piper voice is English-only for now. Other phone languages
        // retain Android TTS rather than being read with an inappropriate English model.
        if (Prefs.neuralVoice(ctx) &&
            Locale.getDefault().language == "en" &&
            NeuralVoiceInstaller.isInstalled(ctx)
        ) {
            speakNeural(chunks, onDone)
            return
        }

        // Offline-only mode fails closed if the Android engine has no local voice,
        // but never fail silently: tell the user exactly why speech was skipped.
        if (Prefs.offlineVoiceOnly(ctx) && voiceList().none { !it.features.contains("networkTts") }) {
            main.post {
                onProblem?.invoke("Orion cannot speak offline because no installed local voice is available. Install voice data in Android Text-to-speech settings, or turn off Offline-only voice.")
                onSpeaking?.invoke(false)
            }
            onDone?.invoke()
            return
        }
        speakWithAndroidTts(chunks, onDone)
    }

    /** Compatibility path if Piper is not installed or native initialization fails. */
    private fun speakWithAndroidTts(chunks: List<String>, onDone: (() -> Unit)?) {
        // Neural-engine failure must not bypass the explicit no-network-TTS preference.
        // Report the missing local voice instead of silently dropping the assistant reply.
        if (Prefs.offlineVoiceOnly(ctx) && voiceList().none { !it.features.contains("networkTts") }) {
            main.post { onProblem?.invoke("Orion cannot speak offline because no installed local voice is available. Install voice data in Android Text-to-speech settings, or turn off Offline-only voice.") }
            onDone?.invoke()
            return
        }
        if (chunks.isEmpty()) {
            onDone?.invoke()
            return
        }
        if (!ttsReady) {
            if (!ttsInitFinished) {
                // TextToSpeech initializes asynchronously. Preserve early replies rather than
                // silently discarding them if the provider responds before the engine is ready.
                queuedSpeech.add(Pair(chunks, onDone))
            } else {
                onProblem?.invoke("Orion could not speak: Android Text-to-speech is unavailable. Check the phone's Text-to-speech output and installed voice data.")
                onDone?.invoke()
            }
            return
        }
        val engine = tts
        if (engine == null) {
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
            if (pendingIds.add(id) && pending.getAndIncrement() == 0) {
                main.post { onSpeaking?.invoke(true) }
            }
            val result = engine.speak(part, TextToSpeech.QUEUE_ADD, null, id)
            if (result != TextToSpeech.SUCCESS) finished(id)
        }
    }

    /**
     * Each sentence is inferred separately, while sherpa-onnx's native callback feeds
     * PCM blocks directly into AudioTrack. Blocking writes add back-pressure; synthesis
     * of the next sentence overlaps playback of the current sentence.
     */
    private fun speakNeural(chunks: List<String>, onDone: (() -> Unit)?) {
        val token = playbackGeneration.get()
        val ids = chunks.mapIndexed { index, _ -> "n" + System.nanoTime() + "_" + index }
        ids.forEach { pendingIds.add(it) }
        if (onDone != null) callbacks[ids.last()] = onDone
        if (pending.getAndAdd(chunks.size) == 0) {
            main.post { if (token == playbackGeneration.get() && pending.get() > 0) onSpeaking?.invoke(true) }
        }

        neuralWorker.execute {
            var track: AudioTrack? = null
            var fallbackStart = 0
            var completedBeforeFallback = 0
            try {
                if (token != playbackGeneration.get()) return@execute
                val model = NeuralVoiceInstaller.modelFile(ctx)
                if (!NeuralVoiceInstaller.isInstalled(ctx)) {
                    throw IllegalStateException("Neural voice files are incomplete.")
                }
                val engine = obtainNeuralEngine(model)
                track = obtainNeuralTrack(engine.sampleRate())
                for (index in chunks.indices) {
                    if (token != playbackGeneration.get()) return@execute
                    fallbackStart = index
                    val generation = GenerationConfig(
                        speed = Prefs.rate(ctx).coerceIn(0.75f, 1.25f)
                    )
                    engine.generateWithConfigAndCallback(
                        text = chunks[index],
                        config = generation,
                        callback = { samples ->
                            if (token != playbackGeneration.get()) {
                                0
                            } else {
                                val activeTrack = track
                                if (activeTrack == null || !writeNeuralSamples(activeTrack, samples, token)) 0
                                else {
                                    if (samples.isNotEmpty()) {
                                        var sumSquares = 0.0
                                        for (sample in samples) sumSquares += sample.toDouble() * sample.toDouble()
                                        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat().coerceIn(0f, 1f)
                                        main.post {
                                            if (token == playbackGeneration.get()) {
                                                onAudioAmplitude?.invoke(rms)
                                                onWord?.invoke()
                                            }
                                        }
                                    }
                                    1
                                }
                            }
                        }
                    )
                    if (token != playbackGeneration.get()) return@execute
                    fallbackStart = index + 1
                    if (index < chunks.lastIndex) {
                        finished(ids[index])
                        completedBeforeFallback++
                    }
                }

                val activeTrack = track
                if (activeTrack != null) awaitNeuralPlayback(activeTrack, neuralFramesWritten, token)
                if (token != playbackGeneration.get()) return@execute
                finished(ids.last())
            } catch (error: Throwable) {
                // Drain any already-written sentence before switching engines so the
                // fallback doesn't overlap neural playback. A stopped turn never falls back.
                if (token != playbackGeneration.get()) return@execute
                try {
                    val activeTrack = track
                    if (activeTrack != null) awaitNeuralPlayback(activeTrack, neuralFramesWritten, token)
                } catch (_: Exception) {
                }
                if (token != playbackGeneration.get()) return@execute
                val startIndex = fallbackStart.coerceIn(0, chunks.size)
                if (startIndex < chunks.size) {
                    val completion = callbacks.remove(ids.last())
                    speakWithAndroidTts(chunks.subList(startIndex, chunks.size), completion)
                    for (index in startIndex until chunks.size) finished(ids[index])
                } else {
                    // This covers a playback-drain failure after every sentence was generated.
                    for (index in completedBeforeFallback until chunks.size) finished(ids[index])
                }
            }
        }
    }

    private fun obtainNeuralEngine(model: java.io.File): OfflineTts {
        val current = neuralEngine
        if (current != null && neuralEnginePath == model.absolutePath) return current
        try { current?.release() } catch (_: Exception) {}
        val config = getOfflineTtsConfig(
            modelDir = model.parentFile?.absolutePath
                ?: throw IllegalStateException("Neural model folder is missing."),
            modelName = model.name,
            acousticModelName = "",
            vocoder = "",
            voices = "",
            lexicon = "lexicon-en-us.txt",
            dataDir = "",
            dictDir = "",
            ruleFsts = "",
            ruleFars = "",
            numThreads = 2
        )
        return OfflineTts(assetManager = null, config = config).also {
            neuralEngine = it
            neuralEnginePath = model.absolutePath
        }
    }

    private fun obtainNeuralTrack(sampleRate: Int): AudioTrack {
        val current = neuralTrack
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED && neuralTrackSampleRate == sampleRate) {
            if (current.playState != AudioTrack.PLAYSTATE_PLAYING) current.play()
            return current
        }
        try { current?.release() } catch (_: Exception) {}
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = maxOf(minimum, sampleRate * 2)
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            throw IllegalStateException("Android could not open audio output for the neural voice.")
        }
        neuralTrack = created
        neuralTrackSampleRate = sampleRate
        neuralFramesWritten = 0L
        created.play()
        return created
    }

    private fun writeNeuralSamples(track: AudioTrack, samples: FloatArray, token: Int): Boolean {
        var offset = 0
        while (offset < samples.size) {
            if (token != playbackGeneration.get()) return false
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                if (token != playbackGeneration.get()) return false
                throw IllegalStateException("AudioTrack could not accept neural voice audio (code " + written + ").")
            }
            offset += written
            neuralFramesWritten += written.toLong()
        }
        return token == playbackGeneration.get()
    }

    private fun awaitNeuralPlayback(track: AudioTrack, targetFrames: Long, token: Int) {
        while (token == playbackGeneration.get()) {
            val played = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (played >= targetFrames) return
            Thread.sleep(12L)
        }
    }

    fun stopSpeaking() {
        playbackGeneration.incrementAndGet()
        queuedSpeech.clear()
        callbacks.clear()
        pendingIds.clear()
        try { tts?.stop() } catch (_: Exception) {}
        val activeTrack = neuralTrack
        neuralTrack = null
        neuralTrackSampleRate = 0
        if (activeTrack != null) {
            try { activeTrack.pause() } catch (_: Exception) {}
            try { activeTrack.flush() } catch (_: Exception) {}
            try { activeTrack.stop() } catch (_: Exception) {}
            try { activeTrack.release() } catch (_: Exception) {}
        }
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
        // Treat an explicit new listen request as barge-in: stop playback before
        // opening the recognizer so Orion does not transcribe its own voice.
        // SpeechRecognizer owns the recording pipeline, so app-level AcousticEchoCanceler
        // cannot be attached here; stopping playback is the reliable platform-safe guard.
        stopSpeaking()
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onProblem?.invoke("No speech recognition on this phone. Install or enable Google voice typing.")
            onText(null)
            return
        }
        val my = ++gen
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Exception) { false }
        // Privacy mode is fail-closed: never silently send microphone audio to a
        // network recognizer when the user explicitly chose offline-only input.
        if (Prefs.offlineSpeechOnly(ctx) && !onDeviceAvailable) {
            onProblem?.invoke("Offline speech recognition is unavailable. Android 12 or newer and an installed on-device speech model are required. Turn off Offline-only speech in Settings to allow the phone's speech service.")
            onText(null)
            return
        }
        // Track the recognizer that was actually created, not just whether Android
        // advertised on-device support. Only fall back to a service when privacy mode
        // is off; strict offline mode must never switch to a network recognizer.
        var usingOnDevice = false
        val offlineOnly = Prefs.offlineSpeechOnly(ctx)
        // Use Android's normal recognizer by default: on-device availability can be reported
        // before its language model is downloaded, which leaves the microphone apparently active
        // but never produces a transcript. Strict offline mode remains on-device-only.
        val r = try {
            if (offlineOnly) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { usingOnDevice = true }
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Exception) {
            if (offlineOnly) {
                onProblem?.invoke("Could not start Android's on-device speech recognizer. Check the offline speech model in system settings.")
                onText(null)
                return
            }
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
        // Prefer offline recognition only when the user explicitly enabled strict offline mode.
        // Otherwise, allow the installed speech service to use its configured online fallback.
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, Prefs.offlineSpeechOnly(ctx))
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
        try {
            neuralWorker.execute {
                try { neuralEngine?.release() } catch (_: Exception) {}
                neuralEngine = null
                neuralEnginePath = null
            }
            neuralWorker.shutdown()
        } catch (_: Exception) {
        }
    }
}
