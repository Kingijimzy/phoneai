package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionShellTranscriptParserTest {
    @Test fun parsesInvisibleSourceAndOutputSeparators() {
        val parsed = OrionShellTranscriptParser.parse(
            "[SHELL:COMPLETE]\n\$ python main.py\n\u2063CODE\nprint('hello')\n\u2063OUT\nhello\n"
        )
        assertEquals("COMPLETE", parsed.phase)
        assertEquals("python main.py", parsed.command)
        assertEquals("print('hello')", parsed.code)
        assertEquals("hello", parsed.output)
        assertFalse(parsed.failed)
        assertFalse(parsed.running)
    }

    @Test fun parsesNewlineNormalizedTranscriptAndFailureState() {
        val parsed = OrionShellTranscriptParser.parse(
            "[SHELL:FAILED]\n\$ python broken.py\n\nCODE\nraise RuntimeError('bad')\nOUT\nTraceback: bad"
        )
        assertEquals("FAILED", parsed.phase)
        assertEquals("python broken.py", parsed.command)
        assertEquals("raise RuntimeError('bad')", parsed.code)
        assertEquals("Traceback: bad", parsed.output)
        assertTrue(parsed.failed)
    }

    @Test fun recognizesRunningStateAndOutputWithoutSource() {
        val parsed = OrionShellTranscriptParser.parse(
            "[SHELL:RUNNING]\n\$ ls -la\n\u2063OUT\nfile.txt"
        )
        assertEquals("ls -la", parsed.command)
        assertEquals("", parsed.code)
        assertEquals("file.txt", parsed.output)
        assertTrue(parsed.running)
    }

    @Test fun keepsPlainLegacyOutputWhenNoSeparatorsExist() {
        val parsed = OrionShellTranscriptParser.parse(
            "[SHELL:DONE]\n\$ pwd\n/data/user/0/com.phoneai/files"
        )
        assertEquals("pwd", parsed.command)
        assertEquals("", parsed.code)
        assertEquals("/data/user/0/com.phoneai/files", parsed.output)
    }
}
