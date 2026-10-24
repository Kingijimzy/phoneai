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

/**
 * Explicit, cancellable installer for the English Piper voice.
 * The app never downloads this model in the background or on first launch.
 */
object NeuralVoiceInstaller {
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low-int8.tar.bz2"
    private const val MODEL_SHA256 = "93070ac9fadf512e56c46bdd0c5d2ce96b424fdc4e683d560167410bd2c4df7d"
    private const val MAX_ARCHIVE_BYTES = 30L * 1024L * 1024L
    private const val MAX_EXTRACTED_FILE_BYTES = 80L * 1024L * 1024L
    private const val MAX_ESPEAK_BYTES = 60L * 1024L * 1024L

    /** Returns the path below espeak-ng-data/ for a safe archive entry, or null if it is not that data. */
    internal fun espeakRelativePath(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val index = parts.indexOf("espeak-ng-data")
        if (index < 0 || index == parts.lastIndex) return null
        val rest = parts.subList(index + 1, parts.size)
        if (rest.any { it == ".." }) return null
        return rest.joinToString("/")
    }

    fun modelDirectory(context: Context): File =
        File(context.filesDir, "orion-neural-voice")

    fun modelFile(context: Context): File =
        File(modelDirectory(context), "amy.onnx")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        return File(dir, ".installed").isFile &&
            modelFile(context).isFile && modelFile(context).length() > 1_000_000L &&
            File(dir, "tokens.txt").isFile && File(dir, "tokens.txt").length() > 100L &&
            hasEspeakData(dir)
    }

    /** Piper voices phonemize with espeak-ng; without this folder the engine cannot start and speech is silent. */
    fun espeakDataDirectory(context: Context): File = File(modelDirectory(context), "espeak-ng-data")

    private fun hasEspeakData(dir: File): Boolean {
        val data = File(dir, "espeak-ng-data")
        return data.isDirectory && File(data, "phontab").isFile && File(data, "phondata").isFile
    }

    /**
     * progress receives (description, percent). completion receives null on success,
     * or the failure/cancellation cause. Callbacks run on the installer worker thread.
     */
    fun install(
        context: Context,
        isCancelled: () -> Boolean,
        progress: (String, Int) -> Unit,
        completion: (Throwable?) -> Unit
    ) {
        val app = context.applicationContext
        Thread({
            val archive = File(app.cacheDir, "orion-piper-amy-int8.tar.bz2")
            val stage = File(app.filesDir, "orion-neural-voice-stage")
            var failure: Throwable? = null
            try {
                checkCancelled(isCancelled)
                stage.deleteRecursively()
                if (!stage.mkdirs() && !stage.isDirectory) {
                    throw IOException("Could not create the neural voice directory.")
                }
                progress("Downloading compact Piper model (about 21 MB)…", 0)
                download(MODEL_URL, archive, MAX_ARCHIVE_BYTES, MODEL_SHA256, isCancelled) { value ->
                    progress("Downloading compact Piper model…", (value * 0.72f).toInt())
                }
                checkCancelled(isCancelled)
                progress("Extracting model, vocabulary and pronunciation data…", 80)
                extractModel(archive, stage, isCancelled)
                archive.delete()
                checkCancelled(isCancelled)

                val model = File(stage, "amy.onnx")
                val tokens = File(stage, "tokens.txt")
                if (!model.isFile || model.length() < 1_000_000L) throw IOException("The downloaded ONNX voice model is missing or incomplete.")
                if (!tokens.isFile || tokens.length() < 100L) throw IOException("The Piper token vocabulary is missing or incomplete.")
                if (!hasEspeakData(stage)) throw IOException("The Piper pronunciation data (espeak-ng-data) is missing or incomplete.")
                File(stage, ".installed").writeText("sherpa-onnx 1.13.8\npiper amy low int8 espeak-ng-data\n")

                checkCancelled(isCancelled)
                progress("Finalizing the on-device voice…", 99)
                val destination = modelDirectory(app)
                val backup = File(app.filesDir, "orion-neural-voice-backup")
                backup.deleteRecursively()
                if (destination.exists() && !destination.renameTo(backup)) {
                    throw IOException("Could not replace the existing neural voice.")
                }
                if (!stage.renameTo(destination)) {
                    backup.renameTo(destination)
                    throw IOException("Could not activate the downloaded neural voice.")
                }
                backup.deleteRecursively()
                progress("Neural voice installed.", 100)
            } catch (error: Throwable) {
                failure = error
            } finally {
                archive.delete()
                if (failure != null) stage.deleteRecursively()
                completion(failure)
            }
        }, "Orion-Neural-Voice-Install").apply { isDaemon = true }.start()
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw InterruptedException("Neural voice installation cancelled.")
    }

    private fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        expectedSha256: String,
        isCancelled: () -> Boolean,
        progress: (Int) -> Unit
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        var received = 0L
        try {
            connection.connectTimeout = 25_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Orion-Android/1.0 (on-device voice installer)")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Download failed (HTTP " + connection.responseCode + ").")
            }
            val expected = connection.contentLengthLong
            if (expected > maxBytes) throw IOException("The voice asset is larger than expected; download cancelled.")
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var lastPercent = -1
                    while (true) {
                        checkCancelled(isCancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > maxBytes) throw IOException("The downloaded voice asset exceeded its safety size limit.")
                        output.write(buffer, 0, count)
                        val percent = if (expected > 0L) ((received * 100L) / expected).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            progress(percent)
                            lastPercent = percent
                        }
                    }
                }
            }
            if (expected > 0L && received != expected) throw IOException("Download ended before the entire voice asset arrived.")
            checkCancelled(isCancelled)
            if (!AssetIntegrity.matchesSha256(temporary, expectedSha256)) {
                throw IOException("SHA-256 verification failed for a Piper voice asset; the download was rejected.")
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun extractModel(archive: File, destination: File, isCancelled: () -> Boolean) {
        var modelExtracted = false
        var tokensExtracted = false
        var espeakBytes = 0L
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile || entry.size <= 0L || entry.size > MAX_EXTRACTED_FILE_BYTES) continue
                // Only extract the expected files; never trust file paths supplied by the tar.
                val base = File(entry.name).name
                val espeakRelative = espeakRelativePath(entry.name)
                val target = when {
                    espeakRelative != null -> {
                        espeakBytes += entry.size
                        if (espeakBytes > MAX_ESPEAK_BYTES) throw IOException("The pronunciation data exceeded its safety size limit.")
                        File(destination, "espeak-ng-data/" + espeakRelative).also { file ->
                            val root = File(destination, "espeak-ng-data").canonicalPath + File.separator
                            if (!file.canonicalPath.startsWith(root)) throw IOException("Unsafe path in the voice archive.")
                            file.parentFile?.mkdirs()
                        }
                    }
                    base.endsWith(".onnx", ignoreCase = true) && base.contains("amy", ignoreCase = true) ->
                        File(destination, "amy.onnx")
                    base.equals("tokens.txt", ignoreCase = true) -> File(destination, "tokens.txt")
                    else -> null
                } ?: continue

                var remaining = entry.size
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (remaining > 0L) {
                        checkCancelled(isCancelled)
                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                if (remaining != 0L) throw IOException("The downloaded model archive contains a truncated file.")
                if (target.name == "amy.onnx") modelExtracted = true
                if (target.name == "tokens.txt") tokensExtracted = true
            }
        }
        if (!modelExtracted || !tokensExtracted || espeakBytes <= 0L) {
            throw IOException("The official Piper archive did not contain the expected ONNX model, tokens and pronunciation data.")
        }
    }
}
