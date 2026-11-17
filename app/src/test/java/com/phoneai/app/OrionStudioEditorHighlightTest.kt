package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionStudioEditorHighlightTest {
    @Test fun detectsCommonPythonTokenTypes() {
        val source = "def main():\n    value = \"Orion\"\n    # keep 42\n    return 42"
        val spans = OrionStudioEditor.highlight("python", source)
        val tokens = spans.map { it.tok }.toSet()
        assertTrue(tokens.contains(OrionStudioEditor.Tok.KEYWORD))
        assertTrue(tokens.contains(OrionStudioEditor.Tok.STRING))
        assertTrue(tokens.contains(OrionStudioEditor.Tok.COMMENT))
        assertTrue(tokens.contains(OrionStudioEditor.Tok.NUMBER))
    }

    @Test fun mapsEditorExtensionsToSharedLexerLanguages() {
        assertEquals("python", OrionStudioEditor.langOf("src/main.py"))
        assertEquals("typescript", OrionStudioEditor.langOf("src/App.tsx"))
        assertEquals("kotlin", OrionStudioEditor.langOf("Main.kt"))
        assertEquals("text", OrionStudioEditor.langOf("README.unknown"))
    }

    @Test fun skipsVeryLargeDocuments() {
        assertTrue(OrionStudioEditor.highlight("python", "x".repeat(200_001)).isEmpty())
    }
}
