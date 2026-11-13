package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test fun rejectsTraversalAndUnrelatedEntries() {
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data/../../evil"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/tokens.txt"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data"))
    }
}
