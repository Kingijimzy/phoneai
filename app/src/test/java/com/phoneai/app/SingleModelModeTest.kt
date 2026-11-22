package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleModelModeTest {
    @Test fun multipleModelsByDefaultAndSingleModelWhenSwitchedOff() {
        val c: Context = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(Prefs.autoSwitch(c))
        Prefs.setAutoSwitch(c, false)
        assertFalse(Prefs.autoSwitch(c))
        Prefs.setAutoSwitch(c, true)
        assertTrue(Prefs.autoSwitch(c))
    }
}
