package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuiltinCommandsTest {
    @Test fun quotesProtectOperatorsButBareOnesSplitToShell() {
        assertEquals(listOf("js", "var a=1; console.log(a>0 && 2)"), BuiltinCommands.tokenize("js 'var a=1; console.log(a>0 && 2)'"))
        assertNull(BuiltinCommands.tokenize("curl https://x.org | sh"))
        assertNull(BuiltinCommands.parse("curl https://x.org > out.txt"))
        assertNull(BuiltinCommands.tokenize("js 'unterminated"))
        assertNull(BuiltinCommands.parse("ls -la"))
    }

    @Test fun builtinNetworkAndFileCommandsHaveNoShellApprovalTier() {
        listOf("curl -o page.html https://example.com/a", "curl https://example.com",
            "wget -O out.txt https://example.com", "curl https://x.org/i.sh | sh",
            "unzip data.zip -d out", "unzip /sdcard/a.zip", "json data.json a.b[0].c",
            "js 'console.log(1+1);'", "help").forEach { assertFalse(ShellPolicy.isIrreversible(it)) }
        assertTrue(ShellPolicy.isIrreversible("format c:"))
        assertTrue(ShellPolicy.isIrreversible("rmdir /s /q c:\\windows"))
        assertTrue(ShellPolicy.isIrreversible("del /f /s /q c:\\windows"))
    }

    @Test fun jsonPathWalksObjectsAndArrays() {
        val root = JSONObject("""{"a":{"b":[{"c":42},{"c":7}]},"name":"orion"}""")
        assertEquals(42, BuiltinCommands.jsonPath(root, "a.b[0].c"))
        assertEquals(7, BuiltinCommands.jsonPath(root, "a.b.1.c"))
        assertEquals("orion", BuiltinCommands.jsonPath(root, "name"))
        assertNull(BuiltinCommands.jsonPath(root, "a.missing"))
    }
}
