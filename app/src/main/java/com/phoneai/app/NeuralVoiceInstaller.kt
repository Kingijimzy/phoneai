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
 * Explicit, cancellable installer for Kokoro's quantized British-English voice model.
 * The model is downloaded only after the owner asks to install it; it is never bundled in the APK.
 */
object NeuralVoiceInstaller {
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
    private const val MODEL_SHA256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
    private const val MAX_ARCHIVE_BYTES = 150L * 1024L * 1024L
    private const val MAX_EXTRACTED_FILE_BYTES = 180L * 1024L * 1024L
    private const val MAX_ESPEAK_BYTES = 60L * 1024L * 1024L

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
        File(modelDirectory(context), "model.int8.onnx")

    fun voicesFile(context: Context): File =
        File(modelDirectory(context), "voices.bin")

    fun espeakDataDirectory(context: Context): File =
        File(modelDirectory(context), "espeak-ng-data")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        val model = modelFile(context)
        val voices = voicesFile(context)
        val tokens = File(dir, "tokens.txt")
        return File(dir, ".installed").isFile &&
            model.isFile && model.length() > 30_000_000L &&
            voices.isFile && voices.length() > 1_000_000L &&
            tokens.isFile && tokens.length() > 100L &&
            File(dir, "LICENSE").isFile && File(dir, "LICENSE").length() > 1_000L &&
            hasEspeakData(dir)
    }

    private fun hasEspeakData(dir: File): Boolean {
        val data = File(dir, "espeak-ng-data")
        return data.isDirectory &&
            File(data, "phontab").isFile &&
            File(data, "phondata").isFile
    }

    fun install(
        context: Context,
        isCancelled: () -> Boolean,
        progress: (String, Int) -> Unit,
        completion: (Throwable?) -> Unit
    ) {
        val app = context.applicationContext
        Thread({
            val archive = File(app.cacheDir, "orion-kokoro-int8-en.tar.bz2")
            val stage = File(app.filesDir, "orion-neural-voice-stage")
            var failure: Throwable? = null
            try {
                checkCancelled(isCancelled)
                stage.deleteRecursively()
                if (!stage.mkdirs() && !stage.isDirectory) {
                    throw IOException("Could not create the neural voice directory.")
                }
                progress("Downloading Kokoro British neural voice (about 104 MB)…", 0)
                download(MODEL_URL, archive, MAX_ARCHIVE_BYTES, MODEL_SHA256, isCancelled) { value ->
                    progress("Downloading verified Kokoro model…", (value * 0.72f).toInt())
                }
                checkCancelled(isCancelled)
                progress("Extracting voice model and pronunciation data…", 80)
                extractModel(archive, stage, isCancelled)
                archive.delete()
                checkCancelled(isCancelled)

                val model = File(stage, "model.int8.onnx")
                val voices = File(stage, "voices.bin")
                val tokens = File(stage, "tokens.txt")
                val license = File(stage, "LICENSE")
                if (!model.isFile || model.length() < 30_000_000L) {
                    throw IOException("The Kokoro INT8 ONNX model is missing or incomplete.")
                }
                if (!voices.isFile || voices.length() < 1_000_000L) {
                    throw IOException("Kokoro's multi-speaker voice embeddings are missing or incomplete.")
                }
                if (!tokens.isFile || tokens.length() < 100L) {
                    throw IOException("Kokoro's token vocabulary is missing or incomplete.")
                }
                if (!license.isFile || license.length() < 1_000L) {
                    throw IOException("The Kokoro model license notice is missing from the downloaded package.")
                }
                if (!hasEspeakData(stage)) {
                    throw IOException("Kokoro's pronunciation data is missing or incomplete.")
                }
                File(stage, ".installed").writeText(
                    "sherpa-onnx 1.13.8\nkokoro int8 en-v0.19\nvoice=bf_emma\n"
                )

                checkCancelled(isCancelled)
                progress("Activating the British neural voice…", 99)
                val destination = modelDirectory(app)
                val backup = File(app.filesDir, "orion-neural-voice-backup")
                backup.deleteRecursively()
                if (destination.exists() && !destination.renameTo(backup)) {
                    throw IOException("Could not replace the existing neural voice.")
                }
                if (!stage.renameTo(destination)) {
                    backup.renameTo(destination)
                    throw IOException("Could not activate the downloaded Kokoro voice.")
                }
                backup.deleteRecursively()
                progress("Kokoro British neural voice installed.", 100)
            } catch (error: Throwable) {
                failure = error
            } finally {
                archive.delete()
                if (failure != null) stage.deleteRecursively()
                completion(failure)
            }
        }, "Orion-Kokoro-Voice-Install").apply { isDaemon = true }.start()
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
            connection.readTimeout = 90_000
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
                    val buffer = ByteArray(32 * 1024)
                    var lastPercent = -1
                    while (true) {
                        checkCancelled(isCancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > maxBytes) {
                            throw IOException("The downloaded voice asset exceeded its safety size limit.")
                        }
                        output.write(buffer, 0, count)
                        val percent = if (expected > 0L) {
                            ((received * 100L) / expected).toInt().coerceIn(0, 100)
                        } else 0
                        if (percent != lastPercent) {
                            progress(percent)
                            lastPercent = percent
                        }
                    }
                }
            }
            if (expected > 0L && received != expected) {
                throw IOException("Download ended before the entire Kokoro model arrived.")
            }
            checkCancelled(isCancelled)
            if (!AssetIntegrity.matchesSha256(temporary, expectedSha256)) {
                throw IOException("SHA-256 verification failed for the Kokoro model; the download was rejected.")
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
        var voicesExtracted = false
        var tokensExtracted = false
        var licenseExtracted = false
        var espeakBytes = 0L
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile || entry.size <= 0L || entry.size > MAX_EXTRACTED_FILE_BYTES) continue
                val base = File(entry.name).name
                val espeakRelative = espeakRelativePath(entry.name)
                val target = when {
                    espeakRelative != null -> {
                        espeakBytes += entry.size
                        if (espeakBytes > MAX_ESPEAK_BYTES) {
                            throw IOException("The pronunciation data exceeded its safety size limit.")
                        }
                        File(destination, "espeak-ng-data/" + espeakRelative).also { file ->
                            val root = File(destination, "espeak-ng-data").canonicalPath + File.separator
                            if (!file.canonicalPath.startsWith(root)) {
                                throw IOException("Unsafe path in the voice archive.")
                            }
                            file.parentFile?.mkdirs()
                        }
                    }
                    base.equals("model.int8.onnx", ignoreCase = true) ->
                        File(destination, "model.int8.onnx")
                    base.equals("voices.bin", ignoreCase = true) ->
                        File(destination, "voices.bin")
                    base.equals("tokens.txt", ignoreCase = true) ->
                        File(destination, "tokens.txt")
                    base.equals("LICENSE", ignoreCase = true) ->
                        File(destination, "LICENSE")
                    else -> null
                } ?: continue

                var remaining = entry.size
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (remaining > 0L) {
                        checkCancelled(isCancelled)
                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                if (remaining != 0L) throw IOException("The Kokoro archive contains a truncated file.")
                when (target.name) {
                    "model.int8.onnx" -> modelExtracted = true
                    "voices.bin" -> voicesExtracted = true
                    "tokens.txt" -> tokensExtracted = true
                    "LICENSE" -> licenseExtracted = true
                }
            }
        }
        if (!modelExtracted || !voicesExtracted || !tokensExtracted || !licenseExtracted || espeakBytes <= 0L) {
            throw IOException("The official Kokoro archive did not contain the expected model, voices, tokens, license and pronunciation data.")
        }
    }
}
