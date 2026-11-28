package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionRhythmTest {
    @Test fun learnedTopicPatternNeverOverridesTheCurrentMessage() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_rhythm", Context.MODE_PRIVATE).edit().clear().commit()
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        repeat(5) { OrionRhythm.record(context, "Check BTC market structure", now + it * 60_000L) }

        val prompt = OrionRhythm.section(context, now)
        assertTrue(prompt.contains("weak historical pattern"))
        assertTrue(prompt.contains("never interrupt a greeting"))
        assertTrue(prompt.contains("unless his current message makes it relevant"))
        assertFalse(prompt.contains("offer it first"))
        assertFalse(prompt.contains("expect it, and if he opens without a task"))
    }
}
