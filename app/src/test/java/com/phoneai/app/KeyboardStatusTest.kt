package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class KeyboardStatusTest {
    private val pkg = "com.phoneai.app"

    @Test fun enabledWhenListedAndCaseInsensitive() {
        assertTrue(KeyboardStatus.enabled(pkg) { listOf("com.other", "COM.PHONEAI.APP") })
    }

    @Test fun deniedOrMissingAccessMeansNotEnabledInsteadOfACrash() {
        assertFalse(KeyboardStatus.enabled(pkg) { throw SecurityException("denied on this Android version") })
        assertFalse(KeyboardStatus.enabled(pkg) { null })
        assertFalse(KeyboardStatus.enabled(pkg) { listOf(null, "com.other") })
        assertFalse(KeyboardStatus.enabled(pkg) { emptyList() })
    }

    @Test fun selectedHandlesIncompleteComponentNames() {
        assertTrue(KeyboardStatus.selected(pkg) { "com.phoneai.app/.OrionKeyboard" })
        assertTrue(KeyboardStatus.selected(pkg) { "com.phoneai.app" })
        assertFalse(KeyboardStatus.selected(pkg) { null })
        assertFalse(KeyboardStatus.selected(pkg) { "" })
        assertFalse(KeyboardStatus.selected(pkg) { "com.other/.Ime" })
        assertFalse(KeyboardStatus.selected(pkg) { throw IllegalStateException("settings unavailable") })
    }
}
