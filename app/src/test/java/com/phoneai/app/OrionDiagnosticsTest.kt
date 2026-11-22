package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

class OrionDiagnosticsTest {
    @Test fun reportShowsFailuresAndMeasuredRecord() {
        val step = OrionRunLog.Step(1, "github_account_read", "t", "d", null, 0L, 1500L, OrionRunLog.Status.FAILED, "error: 404 Not Found\nmore")
        val text = OrionDiagnostics.report(listOf(step), mapOf("github_account_read" to Triple(1, 4, 0L), "weather" to Triple(5, 0, 0L)), now = 2000L)
        assertTrue(text.contains("github_account_read | FAILED"))
        assertTrue(text.contains("error: 404 Not Found"))
        assertTrue(text.indexOf("github_account_read: 1/4") < text.indexOf("weather: 5/0"))
    }

    @Test fun emptyReportSaysSo() {
        val text = OrionDiagnostics.report(emptyList(), emptyMap())
        assertTrue(text.contains("none this session") && text.contains("nothing measured yet"))
    }
}
