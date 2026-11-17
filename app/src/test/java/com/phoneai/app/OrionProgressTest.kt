package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionProgressTest {
    private fun p(line: String, prev: OrionProgress.State = OrionProgress.State(active = true, label = "x")) = OrionProgress.parse(line, prev)

    @Test fun formatsSizes() {
        assertEquals("512 B", OrionProgress.bytes(512))
        assertEquals("1.5 MB", OrionProgress.bytes(1572864))
        assertEquals("2.00 GB", OrionProgress.bytes(2147483648L))
    }

    @Test fun readsAptStages() {
        val need = p("Need to get 45.2 MB of archives.")
        assertTrue(need.size.contains("45.2 MB"))
        assertEquals("Downloaded", p("Fetched 45.2 MB in 3s (15 MB/s)").detail)
        val unpack = p("Unpacking nodejs (18.19.1) ...")
        assertEquals("Unpacking nodejs", unpack.detail); assertEquals(70, unpack.percent)
        assertEquals(90, p("Setting up npm (9.2.0) ...").percent)
    }

    @Test fun readsPipNpmAndPercent() {
        assertTrue(p("Downloading requests-2.0.whl (1.2 MB)").size.contains("1.2 MB"))
        assertEquals(95, p("added 12 packages in 3s").percent)
        assertEquals(42, p("  42% [working]").percent)
    }

    @Test fun unrelatedLinesChangeNothing() {
        val s = OrionProgress.State(active = true, label = "x", detail = "d")
        assertEquals(s, OrionProgress.parse("some log line", s))
        assertEquals(s, OrionProgress.parse("   ", s))
    }

    @Test fun errorsAreSurfaced() {
        assertTrue(p("E: dpkg was interrupted, you must manually run 'dpkg --configure -a'").detail.startsWith("E: dpkg"))
    }

    @Test fun healRunsBeforeAptInstalls() {
        assertTrue(OrionStudio.toolchainScript("node", true)!!.startsWith(OrionStudio.APT_HEAL))
        assertTrue(OrionStudio.packageScript("apt", "install", listOf("git"), true)!!.contains("dpkg --configure -a"))
        assertTrue(OrionStudio.installScript("npm install -g x", true).contains("apt-get install -y npm"))
    }
}
