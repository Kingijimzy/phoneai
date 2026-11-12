package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionModelProviderIntegrationTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After fun tearDown() {
        OrionModelProviderFactories // touch to verify import/compile
    }

    @Test
    fun providerFactoriesCreateNimInstance() {
        val provider: OrionModelProvider = OrionModelProviderFactories.create(context)
        assertNotNull(provider)
        // The production factory wraps Nim with the account-provider router.
        assertTrue(provider is AccountAwareModelProvider)
    }

    @Test
    fun nvidiaProviderConstants() {
        assertEquals("nvidia", NVIDIA_PROVIDER.ID)
        assertEquals("NVIDIA API Catalog", NVIDIA_PROVIDER.LABEL)
        assertEquals("https://integrate.api.nvidia.com/v1", NVIDIA_PROVIDER.BASE)
    }

    @Test
    fun localProviderConstants() {
        assertEquals("local", LOCAL_PROVIDER.ID)
        assertEquals("Local AI (Ollama endpoint)", LOCAL_PROVIDER.LABEL)
        assertEquals("http://127.0.0.1:11434/v1", LOCAL_PROVIDER.BASE)
    }

    @Test
    fun offlineModePrefsWork() {
        assertFalse(Prefs.offlineMode(context))
        Prefs.setOfflineMode(context, true)
        assertTrue(Prefs.offlineMode(context))
        Prefs.setOfflineMode(context, false)
        assertFalse(Prefs.offlineMode(context))
    }

    @Test
    fun failFastOfflinePrefsWork() {
        assertTrue(Prefs.failFastOffline(context))
        Prefs.setFailFastOffline(context, false)
        assertFalse(Prefs.failFastOffline(context))
        Prefs.setFailFastOffline(context, true)
        assertTrue(Prefs.failFastOffline(context))
    }
}
