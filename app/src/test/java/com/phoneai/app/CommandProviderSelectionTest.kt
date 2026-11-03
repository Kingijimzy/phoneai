package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Command mode must inherit the provider the user selected unless an explicit override exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandProviderSelectionTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.clearCommandProvider(context)
        Prefs.setKey(context, "groq", "test-only-key")
    }

    @After fun tearDown() {
        Prefs.setKey(context, "groq", "")
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun configuredFastProviderDoesNotOverrideExplicitNvidiaSelection() {
        Prefs.setProvider(context, false, "nvidia")

        assertEquals("nvidia", Prefs.defaultCommandProvider(context).id)
    }

    @Test fun commandModeInheritsTheSelectedProviderWhenNoOverrideExists() {
        Prefs.setProvider(context, false, "gemini")

        assertEquals("gemini", Prefs.defaultCommandProvider(context).id)
        assertEquals(null, Prefs.commandProviderId(context))
    }
}
