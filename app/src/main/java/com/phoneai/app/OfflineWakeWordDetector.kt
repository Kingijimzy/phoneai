package com.phoneai.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.sqrt

/** Local streaming wake-word capture. Audio never leaves the device; Android SpeechRecognizer is started only after detection. */
internal class OfflineWakeWordDetector(
    context: Context,
    private val onWake: () -> Unit,
    private val onVad: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Synchronized fun start(): Boolean {
        if (running) return true
        if (!WakeWordModelInstaller.isInstalled(app)) return false
        running = true
        worker = Thread({ capture() }, "Orion-Offline-Wake").apply { isDaemon = true; start() }
        return true
    }

    @Synchronized fun stop() {
        running = false
        try { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } } catch (_: Exception) { }
        worker?.interrupt(); worker = null
    }

    private fun capture() {
        var spotter: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var detected = false
        try {
            val dir = WakeWordModelInstaller.modelDirectory(app)
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 1, provider = "cpu", modelType = "zipformer2", modelingUnit = "bpe"
                ),
                keywordsFile = File(dir, "keywords.txt").absolutePath,
                keywordsScore = 1.5f, keywordsThreshold = 0.35f, numTrailingBlanks = 2
            )
            val activeSpotter = KeywordSpotter(config)
            spotter = activeSpotter
            val activeStream = activeSpotter.createStream()
            stream = activeStream
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IllegalStateException("Android could not allocate a 16 kHz microphone buffer.")
            val audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, 8192)).build()
            if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); throw IllegalStateException("Could not initialize the offline wake-word microphone.") }
            recorder = audio
            if (AcousticEchoCanceler.isAvailable()) try { aec = AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true } } catch (_: Exception) { }
            if (NoiseSuppressor.isAvailable()) try { ns = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true } } catch (_: Exception) { }
            audio.startRecording()
            val buffer = ShortArray(maxOf(1600, min / 2))
            var speechActive = false
            while (running) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count == AudioRecord.ERROR_DEAD_OBJECT || count == AudioRecord.ERROR_INVALID_OPERATION) throw IllegalStateException("Microphone capture stopped unexpectedly.")
                if (count <= 0) continue
                var energy = 0.0
                for (i in 0 until count) { val sample = buffer[i] / 32768.0; energy += sample * sample }
                val rms = sqrt(energy / count)
                val speech = rms >= 0.008
                if (speech != speechActive) { speechActive = speech; onVad(speech) }
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                activeStream.acceptWaveform(samples, sampleRate = 16000)
                while (activeSpotter.isReady(activeStream)) {
                    activeSpotter.decode(activeStream)
                    val keyword = activeSpotter.getResult(activeStream).keyword
                    if (keyword.isNotBlank() && keyword.filter { it.isLetterOrDigit() }.equals("ORION", ignoreCase = true)) {
                        activeSpotter.reset(activeStream); detected = true; running = false; break
                    }
                }
                if (detected) break
            }
        } catch (e: Throwable) { if (running) onError(e.message ?: e.javaClass.simpleName) }
        finally {
            running = false
            try { onVad(false) } catch (_: Exception) { }
            try { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop(); it.release() } } catch (_: Exception) { }
            recorder = null
            try { aec?.release() } catch (_: Exception) { }; try { ns?.release() } catch (_: Exception) { }
            try { stream?.release() } catch (_: Exception) { }; try { spotter?.release() } catch (_: Exception) { }
        }
        if (detected) onWake()
    }
}