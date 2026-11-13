package com.phoneai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Opt-in, conversation-only Gemini Live transport. It uses a single-use, short-lived Live token
 * and does not expose Orion phone-control tools to the model. All audio remains on this device
 * except the microphone PCM explicitly streamed to the Live API.
 */
internal class GeminiLiveSession(
    context: Context,
    private val apiKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onTranscript(text: String, fromModel: Boolean)
        /** Partial ASR text is display-only and must never trigger a device action. */
        fun onInterimTranscript(text: String) {}
        fun onInputLevel(level: Float)
        /** Output reset signal; actual playback RMS is supplied by Orion's local Voice engine. */
        fun onOutputLevel(level: Float) {}
        /** Stop local Kokoro speech when the live model reports a user interruption. */
        fun onOutputInterrupted() {}
        fun onUsage(message: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .build()
    private val stopped = AtomicBoolean(false)
    private val audioStarted = AtomicBoolean(false)
    private val modelTextBuffer = GeminiLiveTranscriptBuffer()
    private val transcriptFlushExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GeminiLiveTranscriptFlush").apply { isDaemon = true }
    }
    @Volatile private var transcriptFlush: ScheduledFuture<*>? = null
    @Volatile private var localSpeechActive = false

    @Volatile private var socket: WebSocket? = null
    @Volatile private var setupConfig: JSONObject? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var microphoneEnabled = true
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null

    fun start() {
        if (stopped.get()) return
        listener.onStatus("Requesting one-time Gemini Live access…")
        Thread({
            try {
                val config = buildSetup()
                setupConfig = config
                val token = requestEphemeralToken()
                if (stopped.get()) {
                    disposeClient()
                    return@Thread
                }
                val request = Request.Builder()
                    .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained")
                    .header("Authorization", "Token $token")
                    .build()
                socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (stopped.get()) {
                            webSocket.close(1000, "Session stopped")
                            return
                        }
                        val activeSetup = setupConfig
                        if (activeSetup == null || !webSocket.send(JSONObject().put("setup", activeSetup).toString())) {
                            fail("Gemini Live setup could not be sent. Check the connection and try again.")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!stopped.get()) handleServerMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        if (!stopped.get()) handleServerMessage(bytes.utf8())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!stopped.get()) {
                            val code = response?.code
                            val message = if (code == 429 || t.message.orEmpty().contains("quota", true)) {
                                "Gemini Live quota/rate limit was reached. Check your Gemini project limits; Orion cannot estimate your account's cost."
                            } else {
                                "Gemini Live connection failed" + (code?.let { " (HTTP $it)" } ?: "") + ". Check network and API access."
                            }
                            fail(message)
                        }
                        disposeClient()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!stopped.get()) listener.onStatus("Gemini Live connection closed ($code).")
                        disposeClient()
                    }
                })
            } catch (error: Throwable) {
                if (!stopped.get()) fail(friendlyTokenError(error))
            }
        }, "OrionGeminiLiveConnect").apply { isDaemon = true; start() }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
        listener.onStatus(if (enabled) "Microphone on · Gemini Live" else "Microphone muted")
        if (enabled) listener.onInputLevel(0f)
    }

    /** Keep the silence watchdog aware of speech rendered by Orion's local Kokoro engine. */
    fun setLocalSpeechActive(active: Boolean) {
        localSpeechActive = active
        if (active) lastActivityAtMs = System.currentTimeMillis()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        // Only send realtime input when setup completed and microphone capture actually started.
        // Sending audioStreamEnd before the initial setup message would violate the WebSocket protocol.
        if (audioStarted.get()) {
            try {
                socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            } catch (_: Exception) { }
        }
        val activeSocket = socket
        try { activeSocket?.close(1000, "User ended Gemini Live session") } catch (_: Exception) { }
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        transcriptFlush?.cancel(false)
        transcriptFlushExecutor.shutdownNow()
        modelTextBuffer.clear()
        localSpeechActive = false
        try { echoCanceler?.release() } catch (_: Exception) { }
        echoCanceler = null
        listener.onInputLevel(0f)
        listener.onOutputLevel(0f)
        if (activeSocket == null) disposeClient()
        // Keep OkHttp alive long enough to send the WebSocket close frame.
        // onClosed/onFailure dispose it when a socket was already established.
    }

    private fun activityDetectionConfig(): JSONObject {
        val (start, end) = GeminiLivePolicy.activityDetection(Prefs.geminiMicSensitivity(appContext))
        val config = JSONObject()
        if (!start.endsWith("UNSPECIFIED")) config.put("startOfSpeechSensitivity", start).put("endOfSpeechSensitivity", end)
        return config
    }

    @Volatile private var lastActivityAtMs = System.currentTimeMillis()

    private fun buildSetup(): JSONObject {
        val instructions = buildString {
            append("You are Orion in optional Gemini Live voice mode. ")
            append("Have a natural, concise, useful voice conversation. ")
            append("Your response is returned as text and spoken by Orion's selected local British neural voice. Write speech-ready prose: natural sentences, clear pauses, restrained warmth, and no raw tool traces or unnecessary markup. ")
            append(OrionVoicePersona.INSTRUCTIONS)
            append(" ")
            append("This realtime model does not directly execute phone tools. If the user clearly addresses Orion by name and gives a command, the app will end this realtime session and hand the command to Orion's shared guarded tool executor. ")
            append("Never claim that you performed an action on the device. For action requests without the explicit wake phrase 'Orion', explain briefly that they can say 'Orion, [request]' to hand off to the app-approved tools. ")
            append("Do not request passwords, API keys, PINs, recovery phrases, or one-time codes. ")
            append("\\n\\n")
            append(OrionSessionContextProvider.forLiveVoice(appContext).take(14_000))
            val languagePolicy = Prefs.languagePolicy(appContext)
            if (languagePolicy.isNotBlank()) {
                append("Respect the user's saved language policy: ")
                append(languagePolicy.take(800))
                append(". ")
            }
        }
        val liveCapabilities = OrionProviderContextPolicy.capabilities("gemini", GeminiLivePolicy.MODEL, "gemini_live")
        require(liveCapabilities.audioInput && liveCapabilities.audioOutput) {
            "The configured Gemini Live model is not verified for bidirectional audio."
        }
        val boundedInstructions = ContextWindowBudget.pack(
            listOf(instructions),
            OrionProviderContextPolicy.inputBudget(liveCapabilities, 1_024).coerceAtMost(40_000)
        ).text
        // Gemini supplies the conversation intelligence; Orion's selected local Kokoro
        // voice supplies the actual sound. This keeps one consistent voice identity in every mode.
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put(GeminiLivePolicy.RESPONSE_MODALITY))
            .put("maxOutputTokens", 1024)
        return JSONObject()
            .put("model", GeminiLivePolicy.MODEL)
            .put("generationConfig", generationConfig)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", boundedInstructions))))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", activityDetectionConfig()))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", JSONObject())
            .put(
                "contextWindowCompression",
                JSONObject()
                    .put("triggerTokens", 80_000)
                    .put("slidingWindow", JSONObject().put("targetTokens", 40_000))
            )
    }

    private fun requestEphemeralToken(): String {
        if (apiKey.isBlank()) throw IOException("No Gemini API key is saved.")
        // Constraints lock the token to the model/modality, but a rejected constraint must not
        // make Live unusable: on HTTP 400 retry once with a plain short-lived single-use token.
        val first = postTokenRequest(constrained = true)
        if (first.code == 400) {
            val second = postTokenRequest(constrained = false)
            if (second.code in 200..299) return parseToken(second.body)
            throw IOException(describeTokenFailure(second))
        }
        if (first.code !in 200..299) throw IOException(describeTokenFailure(first))
        return parseToken(first.body)
    }

    private class TokenResponse(val code: Int, val body: String)

    private fun parseToken(body: String): String {
        val token = JSONObject(body).optString("name").trim()
        if (token.isBlank()) throw IOException("Google returned an empty Gemini Live token.")
        return token
    }

    /** Google's own error text (never the key) so a 400 says what was actually wrong. */
    private fun describeTokenFailure(response: TokenResponse): String {
        val text = response.body
        if (response.code == 429 || text.contains("RESOURCE_EXHAUSTED", true) || text.contains("quota", true)) {
            return "Gemini Live quota/rate limit was rejected (HTTP ${response.code}). Check AI Studio quota and billing; no cost estimate is available."
        }
        val detail = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
            .replace(apiKey, "***").take(220)
        val hint = when (response.code) {
            400 -> "Google rejected the request. If the message mentions an API key, create a new Gemini key in AI Studio; ephemeral Live tokens may also be unavailable for this key or region."
            401, 403 -> "The saved Gemini key was refused. Check the key and that the Generative Language API is enabled."
            else -> "Check that the saved Gemini key is valid and Live API access is enabled."
        }
        return "Gemini Live token request failed (HTTP ${response.code}). " + (if (detail.isNotBlank()) "Google says: $detail. " else "") + hint
    }

    private fun postTokenRequest(constrained: Boolean): TokenResponse {
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val body = JSONObject()
            .put("uses", 1)
            .put("expireTime", now.plusMillis(GeminiLivePolicy.TOKEN_EXPIRE_AFTER_MS).toString())
            .put("newSessionExpireTime", now.plusMillis(GeminiLivePolicy.TOKEN_NEW_SESSION_WINDOW_MS).toString())
        if (constrained) {
            body.put("liveConnectConstraints", JSONObject()
                .put("model", GeminiLivePolicy.MODEL)
                .put("config", JSONObject()
                    .put("responseModalities", JSONArray().put(GeminiLivePolicy.RESPONSE_MODALITY))
                    .put("sessionResumption", JSONObject())))
        }
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/auth_tokens").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(16_000) }.orEmpty()
            return TokenResponse(code, responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun handleServerMessage(raw: String) {
        val message = try { JSONObject(raw) } catch (_: Exception) {
            listener.onStatus("Received an unreadable Gemini Live event; continuing.")
            return
        }
        val error = message.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code", 0)
            val text = error.optString("message", "Gemini Live rejected the session.")
            fail(if (code == 429 || text.contains("quota", true) || text.contains("RESOURCE_EXHAUSTED", true)) {
                "Gemini Live quota/rate limit was reached. Check AI Studio usage and limits; Orion cannot estimate the account cost."
            } else {
                "Gemini Live error (code $code). Check model availability and API access."
            })
            return
        }
        if (message.has("setupComplete")) {
            listener.onStatus("Gemini Live connected · microphone streaming")
            startAudioIo()
            return
        }
        message.optJSONObject("usageMetadata")?.let { usage ->
            val total = usage.optLong("totalTokenCount", -1L)
            if (total >= 0L) listener.onUsage("Gemini Live reports $total tokens for the current usage update; this is not a dollar-cost estimate.")
        }
        val serverContent = message.optJSONObject("serverContent") ?: return
        if (serverContent.optBoolean("interrupted", false)) {
            localSpeechActive = false
            transcriptFlush?.cancel(false)
            modelTextBuffer.clear()
            listener.onOutputInterrupted()
        }
        serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
            .takeIf { it.isNotBlank() }?.let { listener.onTranscript(it, false) }
        serverContent.optJSONObject("interimInputTranscription")?.optString("text").orEmpty()
            .takeIf { it.isNotBlank() }?.let { listener.onInterimTranscript(it) }

        // Gemini 3.8 Live returns native audio. The configured output transcription is the
        // authoritative text stream that is handed to Orion's shared local Kokoro voice.
        val outputTranscript = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
        if (outputTranscript.isNotBlank()) {
            appendModelTranscript(outputTranscript)
        } else {
            // Keep compatibility with Live models that emit text parts directly.
            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    appendModelTranscript(part.optString("text"))
                }
            }
        }
        if (serverContent.optBoolean("turnComplete", false)) {
            modelTextBuffer.markTurnComplete()
            scheduleTranscriptFlushIfReady()
            listener.onStatus("Gemini Live ready · speak naturally")
        }
        if (message.has("goAway")) {
            listener.onStatus("Google is preparing to close this Live connection. End the call and start a new session if needed.")
        }
    }

    private fun appendModelTranscript(text: String) {
        modelTextBuffer.append(text)
        scheduleTranscriptFlushIfReady()
    }

    private fun scheduleTranscriptFlushIfReady() {
        if (!modelTextBuffer.isReady() || stopped.get()) return
        transcriptFlush?.cancel(false)
        transcriptFlush = transcriptFlushExecutor.schedule({
            if (stopped.get()) return@schedule
            val completed = modelTextBuffer.drain()
            if (completed.isNotBlank()) listener.onTranscript(completed, true)
        }, GeminiLivePolicy.TRANSCRIPT_FLUSH_QUIET_MS, TimeUnit.MILLISECONDS)
    }

    private fun startAudioIo() {
        if (stopped.get() || !audioStarted.compareAndSet(false, true)) return
        try {
            val inputMin = AudioRecord.getMinBufferSize(
                GeminiLivePolicy.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (inputMin <= 0) throw IOException("Android could not initialize the Gemini Live microphone.")
            val input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(GeminiLivePolicy.INPUT_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(inputMin, GeminiLivePolicy.AUDIO_CHUNK_SAMPLES * 8))
                .build()
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release()
                throw IOException("Android could not initialize the Gemini Live microphone.")
            }
            recorder = input
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true }
                } catch (_: Exception) { }
            }

            recordingThread = Thread({ captureLoop(input) }, "OrionGeminiLiveMicrophone").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            fail(error.message ?: "Could not start Gemini Live audio on this device.")
        }
    }

    private fun captureLoop(input: AudioRecord) {
        val samples = ShortArray(GeminiLivePolicy.AUDIO_CHUNK_SAMPLES)
        var activityEnded = false
        try {
            input.startRecording()
            while (!stopped.get()) {
                if (!microphoneEnabled) {
                    if (!activityEnded) {
                        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
                        activityEnded = true
                    }
                    listener.onInputLevel(0f)
                    Thread.sleep(20L)
                    continue
                }
                val count = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (stopped.get()) break
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) throw IOException("Android microphone stopped unexpectedly.")
                    continue
                }
                activityEnded = false
                var power = 0.0
                val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    val sample = samples[i]
                    pcm.putShort(sample)
                    val normalized = sample.toDouble() / Short.MAX_VALUE
                    power += normalized * normalized
                }
                val rms = sqrt(power / count).toFloat().coerceIn(0f, 1f)
                listener.onInputLevel(rms)
                val nowMs = System.currentTimeMillis()
                if (rms >= GeminiLivePolicy.SPEECH_RMS || localSpeechActive) lastActivityAtMs = nowMs
                if (microphoneEnabled && GeminiLivePolicy.silenceExceeded(nowMs, lastActivityAtMs)) {
                    fail("Gemini Live ended after two minutes of silence to save quota. Start the microphone again when you are ready.")
                    break
                }
                val payload = JSONObject()
                    .put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", Base64.encodeToString(pcm.array(), Base64.NO_WRAP))
                        )
                    )
                if (socket?.send(payload.toString()) != true) {
                    if (!stopped.get()) throw IOException("Gemini Live WebSocket is no longer accepting microphone audio.")
                    break
                }
            }
        } catch (error: Throwable) {
            if (!stopped.get()) fail(error.message ?: "Gemini Live microphone capture failed.")
        } finally {
            try { input.stop() } catch (_: Exception) { }
            try { input.release() } catch (_: Exception) { }
            if (recorder === input) recorder = null
        }
    }

    private fun fail(message: String) {
        if (stopped.get()) return
        listener.onError(message)
        stop()
    }

    private fun friendlyTokenError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("quota", true) || message.contains("rate limit", true) || message.contains("RESOURCE_EXHAUSTED", true) ->
                message.ifBlank { "Gemini Live quota/rate limit reached. Check AI Studio usage and limits." }
            message.contains("No Gemini API key", true) ->
                "No Gemini API key is saved. Add your own Gemini key in Settings, then try again."
            error is java.net.SocketTimeoutException ->
                "Gemini Live token request timed out. Check your network and retry."
            else -> message.ifBlank { "Could not start Gemini Live. Check the Gemini key, API access, and network." }
        }
    }

    private fun disposeClient() {
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) { }
        try { client.connectionPool.evictAll() } catch (_: Exception) { }
    }
}
