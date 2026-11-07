package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionEmbeddingProviderTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
        Prefs.setEmbeddingThirdPartyAllowed(context, false)
    }

    @After fun tearDown() {
        MemoryStore.clear(context)
        Prefs.setEmbeddingThirdPartyAllowed(context, false)
    }

    @Test fun embedReturnsNonNullVectorOfCorrectDimension() {
        val v = OrionEmbeddingProvider.embed(context, "I live in Lagos")
        assertEquals(512, v.size)
        assertEquals(1.0, OrionEmbeddingProvider.cosine(v, v), 1e-4)
    }

    @Test fun embedIsStableForSameText() {
        val a = OrionEmbeddingProvider.embed(context, "My favourite team is Real Madrid")
        val b = OrionEmbeddingProvider.embed(context, "My favourite team is Real Madrid")
        assertEquals(1.0, OrionEmbeddingProvider.cosine(a, b), 1e-4)
    }

    @Test fun paraphraseSimilarityBeatsUnrelated() {
        val team = OrionEmbeddingProvider.similarity(context, "My favourite team is Real Madrid", "which club do I support")
        val rice = OrionEmbeddingProvider.similarity(context, "I love jollof rice for dinner", "which club do I support")
        assertTrue("paraphrase should be more similar than unrelated text", team > rice)
    }

    @Test fun citySimilarityBeatsTrading() {
        val city = OrionEmbeddingProvider.similarity(context, "I live in Lagos", "where is my home city")
        val trading = OrionEmbeddingProvider.similarity(context, "I trade swing positions on Bybit", "where is my home city")
        assertTrue("city memory should be more similar than trading text", city > trading)
    }

    @Test fun thirdPartyEmbeddingDisabledByDefault() {
        assertFalse(Prefs.embeddingThirdPartyAllowed(context))
    }

    @Test fun thirdPartySettingCanBeEnabledAndReadBack() {
        Prefs.setEmbeddingThirdPartyAllowed(context, true)
        assertTrue(Prefs.embeddingThirdPartyAllowed(context))
        Prefs.setEmbeddingThirdPartyAllowed(context, false)
        assertFalse(Prefs.embeddingThirdPartyAllowed(context))
    }

    @Test fun ollamaBaseUrlAndModelHaveDefaults() {
        assertTrue(Prefs.ollamaBaseUrl(context).isNotEmpty())
        assertTrue(Prefs.ollamaEmbeddingModel(context).isNotEmpty())
    }

    @Test fun ollamaSettingsRoundTrip() {
        Prefs.setOllamaBaseUrl(context, "http://127.0.0.1:11434")
        Prefs.setOllamaEmbeddingModel(context, "nomic-embed-text")
        assertEquals("http://127.0.0.1:11434", Prefs.ollamaBaseUrl(context))
        assertEquals("nomic-embed-text", Prefs.ollamaEmbeddingModel(context))
    }
}
