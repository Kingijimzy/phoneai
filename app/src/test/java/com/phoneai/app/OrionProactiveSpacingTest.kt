package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionProactiveSpacingTest {
    private val min = 60_000L

    @Test fun needsFifteenQuietMinutesAndTwentyBetweenMessages() {
        val now = 10_000 * min
        assertTrue(OrionProactiveSpacing.allowed(now, 0, 0))
        assertFalse(OrionProactiveSpacing.allowed(now, now - 14 * min, 0))
        assertTrue(OrionProactiveSpacing.allowed(now, now - 15 * min, 0))
        assertFalse(OrionProactiveSpacing.allowed(now, 0, now - 19 * min))
        assertTrue(OrionProactiveSpacing.allowed(now, 0, now - 20 * min))
    }

    @Test fun claimingRecordsTheMessageSoTheNextOneIsHeldBack() {
        val c: Context = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("orion_proactive_spacing", Context.MODE_PRIVATE).edit().clear().commit()
        val t0 = 1_000_000 * min
        assertTrue(OrionProactiveSpacing.tryClaim(c, t0))
        assertFalse(OrionProactiveSpacing.tryClaim(c, t0 + 19 * min))
        assertTrue(OrionProactiveSpacing.tryClaim(c, t0 + 20 * min))
    }

    @Test fun recentOwnerActivityKeepsOrionQuiet() {
        val c: Context = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("orion_proactive_spacing", Context.MODE_PRIVATE).edit().clear().commit()
        val t0 = 2_000_000 * min
        OrionProactiveSpacing.noteOwnerActivity(c, t0)
        assertFalse(OrionProactiveSpacing.tryClaim(c, t0 + 5 * min))
        assertEquals(true, OrionProactiveSpacing.tryClaim(c, t0 + 16 * min))
    }
}
