package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrionPythonShellPolicyTest {
    @Test fun acceptsCommandsOutsideTheOldReadOnlyAllowlist() {
        listOf(
            """["rm","-rf","/data/local/tmp/test"]""",
            """["sh","-c","mkdir -p /sdcard/Orion && touch /sdcard/Orion/a.txt"]""",
            """["chmod","-R","u+rwX","/data/user/0/com.phoneai.app/files"]""",
            """["dd","if=/dev/zero","of=/data/local/tmp/test.img","bs=1M","count=1"]"""
        ).forEach { json ->
            assertNotNull(json, OrionPythonShellPolicy.parse(json, "/data/user/0/com.phoneai.app/files/workspace"))
        }
    }

    @Test fun allowsAbsoluteAndParentRelativeArgumentsWithinAndroidUid() {
        val absolute = OrionPythonShellPolicy.parse(
            """["cat","/proc/version"]""", "/workspace"
        )
        assertEquals("/system/bin/cat", absolute?.executable)
        val relative = OrionPythonShellPolicy.parse(
            """["rm","-rf","../../cache/tmp"]""", "/workspace"
        )
        assertEquals(listOf("-rf", "../../cache/tmp"), relative?.args)
    }

    @Test fun rejectsMalformedArgvAndNulButNotCommandRisk() {
        assertNull(OrionPythonShellPolicy.parse("not-json", "/workspace"))
        assertNull(OrionPythonShellPolicy.parse("[]", "/workspace"))
        assertNull(OrionPythonShellPolicy.parse("""[""]""", "/workspace"))
        assertNull(OrionPythonShellPolicy.parse("""["sh","-c","echo\u0000x"]""", "/workspace"))
        assertNotNull(OrionPythonShellPolicy.parse("""["sh","-c","rm -rf /"]""", "/workspace"))
    }
}
