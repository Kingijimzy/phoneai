package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.nio.file.Files

class NeuralVoiceInstallerTest {
    @Test fun mapsEspeakDataEntriesBelowTheDataFolder() {
        assertEquals("phontab", NeuralVoiceInstaller.espeakRelativePath("vits-piper-en_US-amy-low-int8/espeak-ng-data/phontab"))
        assertEquals("voices/!v/m1", NeuralVoiceInstaller.espeakRelativePath("./vits-piper-en_US-amy-low-int8/espeak-ng-data/voices/!v/m1"))
    }

    @Test fun extractionProgressTracksCompressedArchiveConsumption() {
        assertEquals(80, NeuralVoiceInstaller.extractionProgressPercent(0L, 100L))
        assertEquals(89, NeuralVoiceInstaller.extractionProgressPercent(50L, 100L))
        assertEquals(98, NeuralVoiceInstaller.extractionProgressPercent(100L, 100L))
        assertEquals(98, NeuralVoiceInstaller.extractionProgressPercent(150L, 100L))
        assertEquals(80, NeuralVoiceInstaller.extractionProgressPercent(50L, 0L))
    }

    @Test fun extractsRequiredKokoroAssetsAndReportsExtractionProgress() {
        val root = Files.createTempDirectory("orion-kokoro-test").toFile()
        try {
            val archive = File(root, "model.tar.bz2")
            val assets = listOf(
                "package/model.int8.onnx" to ByteArray(1024) { 1 },
                "package/voices.bin" to ByteArray(512) { 2 },
                "package/tokens.txt" to "token vocabulary".toByteArray(),
                "package/LICENSE" to ByteArray(1024) { 3 },
                "package/espeak-ng-data/phontab" to "pronunciation data".toByteArray()
            )
            BZip2CompressorOutputStream(archive.outputStream().buffered()).use { compressed ->
                TarArchiveOutputStream(compressed).use { tar ->
                    assets.forEach { (name, bytes) ->
                        val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
            val destination = File(root, "extracted").apply { mkdirs() }
            val progress = mutableListOf<Int>()
            NeuralVoiceInstaller.extractModel(archive, destination, { false }) { progress += it }
            assertTrue(File(destination, "model.int8.onnx").isFile)
            assertTrue(File(destination, "voices.bin").isFile)
            assertTrue(File(destination, "tokens.txt").isFile)
            assertTrue(File(destination, "LICENSE").isFile)
            assertTrue(File(destination, "espeak-ng-data/phontab").isFile)
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.all { it in 80..98 })
            assertEquals(98, progress.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAndUnrelatedEntries() {
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data/../../evil"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/tokens.txt"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data"))
    }
}
