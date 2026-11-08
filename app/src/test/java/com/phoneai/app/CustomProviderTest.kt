package com.phoneai.app

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomProviderTest {
    private val c: Context = RuntimeEnvironment.getApplication()

    @Test fun customProviderJoinsEveryListAndCanBeRemoved() {
        Providers.custom = emptyList()
        val id = Prefs.addCustomProvider(c, "Wus Router", "https://wusrouter.com/v1/", "deepseek-v4.1-flash", false, "k-123")!!
        assertEquals("custom_wus_router", id)
        val p = Providers.find(id)!!
        assertEquals("https://wusrouter.com/v1", p.base)
        assertEquals("deepseek-v4.1-flash", p.textModel)
        assertFalse(p.requiresPaidOptIn)
        assertTrue(Providers.all.any { it.id == id })
        Providers.custom = emptyList()
        Prefs.loadCustomProviders(c)
        assertNotNull("must survive an app restart", Providers.find(id))
        Prefs.removeCustomProvider(c, id)
        assertNull(Providers.find(id))
    }

    @Test fun badUrlIsRejected() {
        assertNull(Prefs.addCustomProvider(c, "x", "not-a-url", "m", false, ""))
    }
}
