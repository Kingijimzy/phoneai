package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/** Decides when sustained speech over Orion's own voice counts as the owner cutting in. Pure and unit-tested. */
internal class OrionBargeInPolicy(
    private val warmupFrames: Int = 15,
    private val triggerFrames: Int = 14,
    private val minThreshold: Float = 0.035f,
    private val floorMultiple: Float = 3.0f
) {
    private var frames = 0
    private var floor = 0f
    private var run = 0

    fun onFrame(rms: Float): Boolean {
        frames++
        if (frames <= warmupFrames) {
            // Calibrate on Orion's own leakage (speaker -> mic after echo cancellation).
            floor = if (frames == 1) rms else floor * 0.8f + rms * 0.2f
            return false
        }
        val threshold = maxOf(floor * floorMultiple, minThreshold)
        if (rms > threshold) run++ else {
            run = maxOf(0, run - 1)
            floor = floor * 0.99f + rms * 0.01f
        }
        return run >= triggerFrames
    }
}

/**
 * Listens while Orion speaks, using the platform echo canceller and noise suppressor on a voice-communication
 * stream, so the owner can talk over Orion and be heard without Orion hearing itself.
 */
internal class OrionBargeInMonitor(private val ctx: Context) {
    @Volatile private var thread: Thread? = null

    fun start(onBarge: () -> Unit) {
        if (thread != null) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val t = Thread {
            var rec: AudioRecord? = null
            try {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) return@Thread
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@Thread
                val session = rec.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session)?.enabled = true }
                if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session)?.enabled = true }
                if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session)?.enabled = false }
                rec.startRecording()
                val policy = OrionBargeInPolicy()
                val frame = ShortArray(320)
                while (thread === Thread.currentThread()) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = frame[i] / 32768.0; sum += v * v }
                    if (policy.onFrame(Math.sqrt(sum / n).toFloat())) {
                        thread = null
                        onBarge()
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
            }
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() { thread = null }
}
