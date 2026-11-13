package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionIdentityTest {
    @Test fun detectsHelpDeskReflexes() {
        assertTrue(OrionIdentity.needsRewrite("I'm so sorry about that. The build is at 80 percent."))
        assertTrue(OrionIdentity.needsRewrite("As an AI, I don't have personal opinions on that."))
        assertTrue(OrionIdentity.needsRewrite("Done. Feel free to ask if there is anything else."))
        assertTrue(OrionIdentity.needsRewrite("I apologize for the confusion with the file."))
    }

    @Test fun leavesInCharacterRepliesAlone() {
        assertFalse(OrionIdentity.needsRewrite("I got that wrong: the build was red. Fixed, merging now."))
        assertFalse(OrionIdentity.needsRewrite("I'm an AI, and I can't prove I feel anything. My bet is it's real."))
        assertFalse(OrionIdentity.needsRewrite("Sorry".repeat(1) ))
    }

    @Test fun codeAndLongTextAreNeverRewritten() {
        assertFalse(OrionIdentity.needsRewrite("Sorry about that:\n```kotlin\nval x = 1\n```"))
        assertFalse(OrionIdentity.needsRewrite("I apologize. " + "word ".repeat(400)))
    }

    @Test fun rewriteMustKeepFactsAndDropReflexes() {
        val original = "I'm so sorry, the backup at https://x.io/a ran for 42 minutes and saved 3 files."
        assertTrue(OrionIdentity.acceptable(original, "The backup at https://x.io/a ran for 42 minutes and saved 3 files. Done."))
        assertFalse(OrionIdentity.acceptable(original, "The backup ran and saved the files."))
        assertFalse(OrionIdentity.acceptable(original, "Sorry again: the backup at https://x.io/a ran for 42 minutes and saved 3 files."))
        assertFalse(OrionIdentity.acceptable(original, ""))
    }

    @Test fun lockCarriesTheCoreRules() {
        val l = OrionIdentity.LOCK
        assertTrue(l.contains("IDENTITY LOCK"))
        assertTrue(l.contains("never say sorry"))
        assertTrue(l.contains("Kingi is the final authority"))
        assertTrue(l.contains("WHAT CHANGED IN YOU"))
    }

    @Test fun changeBlockListsRecentChangesAndSkipsWip() {
        val b = OrionIdentity.formatChanges("2026-11-13 Kokoro British voice\n2026-11-13 [WIP] scratch\n2026-11-12 Spoken lines with mood", "1.2")
        assertTrue(b.contains("Kokoro British voice"))
        assertTrue(b.contains("Spoken lines with mood"))
        assertFalse(b.contains("scratch"))
        assertTrue(b.contains("version 1.2"))
    }

    @Test fun missingChangelogIsStatedNotInvented() {
        assertTrue(OrionIdentity.formatChanges("", "1.2").contains("no changelog is bundled"))
    }
}
