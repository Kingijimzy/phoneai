package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeuralVoiceInstallerTest {
    @Test fun mapsEspeakDataEntriesBelowTheDataFolder() {
        assertEquals("phontab", NeuralVoiceInstaller.espeakRelativePath("vits-piper-en_US-amy-low-int8/espeak-ng-data/phontab"))
        assertEquals("voices/!v/m1", NeuralVoiceInstaller.espeakRelativePath("./vits-piper-en_US-amy-low-int8/espeak-ng-data/voices/!v/m1"))
    }

    @Test fun rejectsTraversalAndUnrelatedEntries() {
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data/../../evil"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/tokens.txt"))
        assertNull(NeuralVoiceInstaller.espeakRelativePath("x/espeak-ng-data"))
    }
}
