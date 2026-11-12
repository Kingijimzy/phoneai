package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionVoiceOutputPolicyTest {
    @Test fun keepsConciseNaturalAnswer() {
        assertEquals(
            "The file is created. I checked that it runs.",
            OrionVoiceOutputPolicy.forSpeech("The file is created. I checked that it runs. Here is the complete implementation and explanation.")
        )
    }

    @Test fun neverReadsCodeFenceAloud() {
        val answer = "I created the calculator and tested it.\n\n```python\ndef add(a, b):\n    return a + b\n```"
        assertEquals("I created the calculator and tested it.", OrionVoiceOutputPolicy.forSpeech(answer))
    }

    @Test fun punctuationInsideCodeCannotLeakIntoSpeech() {
        val answer = """The result is ready. ```python
print("hello.world")
```"""
        assertEquals("The result is ready.", OrionVoiceOutputPolicy.forSpeech(answer))
    }

    @Test fun suppressesCodeOnlyOutput() {
        val answer = "```kotlin\nfun add(a: Int, b: Int) = a + b\n```"
        assertNull(OrionVoiceOutputPolicy.forSpeech(answer))
    }

    @Test fun removesUrlsAndInlineCode() {
        assertEquals(
            "The build passed. I saved the result as a file.",
            OrionVoiceOutputPolicy.forSpeech("The build passed. Run `./gradlew test` and see https://example.com/build. I saved the result as a file.")
        )
    }

    @Test fun progressIsOneShortAcknowledgement() {
        OrionVoiceOutputPolicy.beginTask()
        assertEquals("I'm on it.", OrionVoiceOutputPolicy.progressCue("Running Python, reading output and validating files…"))
        assertNull(OrionVoiceOutputPolicy.progressCue(""))
    }

    @Test fun progressCueIsEmittedOnlyOncePerOwnerTurn() {
        OrionVoiceOutputPolicy.beginTask()
        assertEquals("I'm on it.", OrionVoiceOutputPolicy.progressCue("Starting work"))
        assertNull(OrionVoiceOutputPolicy.progressCue("Still working"))
        OrionVoiceOutputPolicy.beginTask()
        assertEquals("I'm on it.", OrionVoiceOutputPolicy.progressCue("New task"))
    }

    @Test fun voicePersonaSeparatesSpeechFromVisibleTechnicalDetails() {
        val prompt = OrionVoicePersona.INSTRUCTIONS
        assertTrue(prompt.contains("one to three useful sentences"))
        assertTrue(prompt.contains("tool names"))
        assertTrue(prompt.contains("full technical explanation remains in chat"))
        assertFalse(prompt.contains("read every tool"))
    }
}
