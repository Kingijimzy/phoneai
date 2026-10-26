package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionKeyboardPolicyTest {
    @Test fun refusesEmptyAndOverlongText() {
        assertEquals("error: refusing to type empty text", OrionKeyboardPolicy.validateRequest(""))
        assertEquals(
            "error: text exceeds the 4000 character limit",
            OrionKeyboardPolicy.validateRequest("x".repeat(4001))
        )
        assertNull(OrionKeyboardPolicy.validateRequest("Hello Orion"))
        assertNull(OrionKeyboardPolicy.validateRequest("x".repeat(4000)))
    }

    @Test fun blocksPasswordFieldsEvenWhenPackageMatches() {
        val result = OrionKeyboardPolicy.validateFocusedEditor(
            targetPackage = "com.example.chat",
            focusedPackage = "com.example.chat",
            isPassword = true,
            hasConnection = true
        )
        assertEquals("error: refusing to type into a password field", result)
    }

    @Test fun blocksUnexpectedFocusedPackageAndMissingConnection() {
        assertTrue(
            OrionKeyboardPolicy.validateFocusedEditor(
                "com.example.chat", "com.example.other", false, true
            )!!.contains("focused editor changed")
        )
        assertTrue(
            OrionKeyboardPolicy.validateFocusedEditor(
                null, "com.example.chat", false, false
            )!!.contains("no input connection")
        )
    }

    @Test fun onlyAllowsReplacementAfterSelectAllSucceeds() {
        assertFalse(OrionKeyboardPolicy.canReplaceField(false))
        assertTrue(OrionKeyboardPolicy.canReplaceField(true))
    }
}
