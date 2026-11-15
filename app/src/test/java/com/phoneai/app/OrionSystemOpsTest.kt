package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import com.phoneai.app.OrionSystemOps.Route as R

class OrionSystemOpsTest {
    @Test fun routesByPower() {
        assertEquals(R.SHIZUKU, OrionSystemOps.classify("adb shell cmd wifi set-wifi-enabled disabled"))
        assertEquals(R.SHIZUKU, OrionSystemOps.classify("settings put global airplane_mode_on 1"))
        assertEquals(R.SHIZUKU, OrionSystemOps.classify("pm list packages -3"))
        assertEquals(R.LINUX, OrionSystemOps.classify("ffmpeg -i input.mp4 output.mp3"))
        assertEquals(R.LINUX, OrionSystemOps.classify("git clone https://github.com/a/b"))
        assertEquals(R.PYTHON, OrionSystemOps.classify("python: print(1+1)"))
        assertEquals(R.PYTHON, OrionSystemOps.classify("import requests\nprint(requests.get('x').text)"))
    }
    @Test fun chainFallsDownTheHierarchy() {
        assertEquals(listOf(R.SHIZUKU, R.LINUX, R.ACCESSIBILITY), OrionSystemOps.chain("cmd wifi status", null))
        assertEquals(listOf(R.LINUX, R.SHIZUKU, R.ACCESSIBILITY), OrionSystemOps.chain("ffmpeg -i a b", null))
        assertEquals(listOf(R.PYTHON, R.LINUX, R.ACCESSIBILITY), OrionSystemOps.chain("python: x", null))
        assertEquals(R.LINUX, OrionSystemOps.chain("cmd wifi status", R.LINUX).first())
    }
    @Test fun routeAliasesAndAdbStripping() {
        assertEquals(R.SHIZUKU, OrionSystemOps.parseRoute("ADB"))
        assertEquals(R.LINUX, OrionSystemOps.parseRoute("proot"))
        assertNull(OrionSystemOps.parseRoute("auto"))
        assertEquals("cmd wifi status", OrionSystemOps.stripAdb("adb shell cmd wifi status"))
    }
    @Test fun failureDetection() {
        assertTrue(OrionSystemOps.failed("error: Shizuku is not running"))
        assertFalse(OrionSystemOps.failed("ok"))
        assertFalse(OrionSystemOps.failed("Wi-Fi is enabled"))
    }
    @Test fun pagesResolveByNameAndKeyword() {
        assertEquals("terminal", OrionPages.find("shell")?.id)
        assertEquals("agents", OrionPages.find("subagent")?.id)
        assertEquals("accounts", OrionPages.find("facebook")?.id)
        assertTrue(OrionPages.search("voice").any { it.id == "voice" })
        assertNull(OrionPages.find("nope-page"))
    }
}
