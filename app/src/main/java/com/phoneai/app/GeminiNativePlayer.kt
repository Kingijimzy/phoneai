package com.phoneai.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Plays the Live model's own audio as it arrives (24 kHz mono PCM). This is the difference between a
 * conversation and text-to-speech: the voice, pacing, laughter and emphasis are the model's, streamed chunk by
 * chunk, and a flush on interruption stops mid-word the moment the owner talks over it.
 */
internal class GeminiNativePlayer(private val onLevel: (Float) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>(GeminiLivePolicy.PLAYBACK_QUEUE_CAPACITY)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var thread: Thread? = null

    val playing: Boolean get() = running && queue.isNotEmpty()

    fun start() {
        if (running) return
        val rate = GeminiLivePolicy.OUTPUT_SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        t.play()
        running = true
        thread = Thread({ loop(t) }, "OrionGeminiNativePlayer").apply { isDaemon = true; start() }
    }

    fun enqueue(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        // A full queue means the owner is far behind; drop the oldest rather than build up delay.
        while (!queue.offer(pcm)) queue.poll()
    }

    /** Owner interrupted: stop mid-word and drop everything not yet played. */
    fun flush() {
        generation++
        queue.clear()
        runCatching { track?.pause(); track?.flush(); track?.play() }
        onLevel(0f)
    }

    fun stop() {
        running = false
        queue.clear()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        onLevel(0f)
    }

    private fun loop(t: AudioTrack) {
        var idleReported = true
        while (running) {
            val chunk = try { queue.poll(120, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            if (chunk == null) {
                if (!idleReported) { onLevel(0f); idleReported = true }
                continue
            }
            val gen = generation
            idleReported = false
            onLevel(rms(chunk))
            var off = 0
            while (off < chunk.size && running && gen == generation) {
                val n = t.write(chunk, off, chunk.size - off)
                if (n <= 0) break
                off += n
            }
        }
    }

    companion object {
        fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0
                sum += v * v; n++; i += 2
            }
            return if (n == 0) 0f else (sqrt(sum / n).toFloat() * 3f).coerceIn(0f, 1f)
        }
    }
}
