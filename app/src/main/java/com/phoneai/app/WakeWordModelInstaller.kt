package com.phoneai.app

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Explicit installer for the small, English sherpa-onnx KWS model. Never downloads in the background. */
object WakeWordModelInstaller {
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2"
    private const val MAX_ARCHIVE_BYTES = 20L * 1024L * 1024L
    private const val MAX_EXTRACTED_FILE_BYTES = 20L * 1024L * 1024L
    private val required = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "keywords.txt")

    fun modelDirectory(context: Context) = File(context.filesDir, "orion-offline-wake")
    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        return File(dir, ".installed").isFile && required.all { name -> File(dir, name).isFile && File(dir, name).length() > if (name == "keywords.txt") 5L else 50L }
    }

    fun install(context: Context, isCancelled: () -> Boolean, progress: (String, Int) -> Unit, completion: (Throwable?) -> Unit) {
        val app = context.applicationContext
        Thread({
            val archive = File(app.cacheDir, "orion-kws-gigaspeech-mobile.tar.bz2")
            val stage = File(app.filesDir, "orion-offline-wake-stage")
            var failure: Throwable? = null
            try {
                checkCancelled(isCancelled)
                stage.deleteRecursively()
                if (!stage.mkdirs() && !stage.isDirectory) throw IOException("Could not create wake-word model directory.")
                progress("Downloading English offline wake-word model (about 16 MB)…", 0)
                download(archive, isCancelled) { progress("Downloading English offline wake-word model…", (it * 0.65f).toInt()) }
                checkCancelled(isCancelled)
                progress("Extracting compact INT8 model…", 68)
                extract(archive, stage, isCancelled)
                archive.delete()
                val tokens = File(stage, "tokens.txt")
                val vocab = tokens.readText()
                if (!vocab.lineSequence().any { it.trimStart().startsWith("▁OR ") } || !vocab.lineSequence().any { it.trimStart().startsWith("ION ") }) {
                    throw IOException("The downloaded keyword vocabulary does not contain the expected ORION tokens; refusing to enable a mismatched model.")
                }
                // GigaSpeech's BPE vocabulary encodes ORION as ▁OR + ION. Keep the trigger conservative.
                File(stage, "keywords.txt").writeText("▁OR ION :1.5 #0.35\n")
                for (name in required) { val minBytes = if (name == "keywords.txt") 5L else 50L; if (!File(stage, name).isFile || File(stage, name).length() <= minBytes) throw IOException("Wake-word asset is missing or incomplete: $name") }
                File(stage, ".installed").writeText("sherpa-onnx 1.13.8; GigaSpeech KWS INT8; keyword ORION\n")
                checkCancelled(isCancelled)
                progress("Activating offline wake-word model…", 96)
                val destination = modelDirectory(app)
                val backup = File(app.filesDir, "orion-offline-wake-backup")
                backup.deleteRecursively()
                if (destination.exists() && !destination.renameTo(backup)) throw IOException("Could not back up the existing wake-word model.")
                if (!stage.renameTo(destination)) { backup.renameTo(destination); throw IOException("Could not activate the wake-word model.") }
                backup.deleteRecursively()
                progress("Offline wake-word model installed.", 100)
            } catch (e: Throwable) { failure = e }
            finally { archive.delete(); if (failure != null) stage.deleteRecursively(); completion(failure) }
        }, "Orion-KWS-Install").apply { isDaemon = true }.start()
    }

    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw InterruptedException("Wake-word installation cancelled.") }
    private fun download(destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val part = File(destination.parentFile, destination.name + ".part")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        var received = 0L
        try {
            connection.connectTimeout = 25000; connection.readTimeout = 60000; connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Orion-Android/1.0 offline wake-word installer")
            connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("Wake-word download failed (HTTP ${connection.responseCode}).")
            val expected = connection.contentLengthLong
            if (expected > MAX_ARCHIVE_BYTES) throw IOException("Wake-word archive exceeds the allowed download size.")
            connection.inputStream.buffered().use { input -> part.outputStream().buffered().use { output ->
                val buffer = ByteArray(16 * 1024); var last = -1
                while (true) { checkCancelled(cancelled); val count = input.read(buffer); if (count < 0) break
                    received += count; if (received > MAX_ARCHIVE_BYTES) throw IOException("Wake-word download exceeded its size limit.")
                    output.write(buffer, 0, count); val pct = if (expected > 0L) (received * 100L / expected).toInt() else 0
                    if (pct != last) { progress(pct.coerceIn(0, 100)); last = pct }
                }
            } }
            if (expected > 0 && received != expected) throw IOException("Wake-word download ended before all bytes arrived.")
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) part.copyTo(destination, overwrite = true)
        } finally { connection.disconnect(); part.delete() }
    }

    private fun extract(archive: File, destination: File, cancelled: () -> Boolean) {
        val names = mapOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to "encoder.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to "decoder.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" to "joiner.int8.onnx",
            "tokens.txt" to "tokens.txt"
        )
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                checkCancelled(cancelled)
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile || entry.size <= 0 || entry.size > MAX_EXTRACTED_FILE_BYTES) continue
                val targetName = names[File(entry.name).name] ?: continue
                val target = File(destination, targetName)
                var remaining = entry.size
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (remaining > 0) { checkCancelled(cancelled); val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) break; out.write(buffer, 0, n); remaining -= n }
                }
                if (remaining != 0L) throw IOException("Incomplete model asset: $targetName")
            }
        }
    }
}