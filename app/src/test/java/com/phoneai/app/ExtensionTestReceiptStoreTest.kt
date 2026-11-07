package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionTestReceiptStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("orion_extension_test_receipts.json").delete()
    }

    @Test fun passingReceiptSurvivesStoreRecreationAndIsBoundToExactSourceHash() {
        val hash = SelfExtendingExtensionPolicy.sha256("def run(value): return value")
        val first = ExtensionTestReceiptStore(context)
        assertTrue(first.record("format_helper", ExtensionTestReport(true, hash, "2/2 passed", "ok")))

        val afterRestart = ExtensionTestReceiptStore(context)
        val restored = afterRestart.get("format_helper", hash)
        assertNotNull(restored)
        assertTrue(restored!!.passed)
        assertEquals(hash, restored.testedSourceSha256)
        assertEquals("2/2 passed", restored.summary)
        assertNull(afterRestart.get("format_helper", SelfExtendingExtensionPolicy.sha256("changed source")))
    }

    @Test fun failedReportsAreNeverPersistedAsPromotionEvidence() {
        val store = ExtensionTestReceiptStore(context)
        val hash = SelfExtendingExtensionPolicy.sha256("source")
        assertFalse(store.record("format_helper", ExtensionTestReport(false, hash, "failed")))
        assertNull(store.get("format_helper", hash))
    }

    @Test fun multipleVerifiedSourceHashesRemainAvailableForRollback() {
        val store = ExtensionTestReceiptStore(context)
        val oldHash = SelfExtendingExtensionPolicy.sha256("old source")
        val newHash = SelfExtendingExtensionPolicy.sha256("new source")
        assertTrue(store.record("format_helper", ExtensionTestReport(true, oldHash, "old passed")))
        assertTrue(store.record("format_helper", ExtensionTestReport(true, newHash, "new passed")))
        assertNotNull(store.get("format_helper", oldHash))
        assertNotNull(store.get("format_helper", newHash))
        assertTrue(store.remove("format_helper", newHash))
        assertNotNull(store.get("format_helper", oldHash))
        assertNull(store.get("format_helper", newHash))
    }

    @Test fun receiptCanBeRevoked() {
        val store = ExtensionTestReceiptStore(context)
        val hash = SelfExtendingExtensionPolicy.sha256("source")
        assertTrue(store.record("format_helper", ExtensionTestReport(true, hash, "passed")))
        assertTrue(store.remove("format_helper"))
        assertNull(store.get("format_helper", hash))
    }

    @Test fun corruptReceiptStoreFailsClosedWithoutReplacingEvidence() {
        val file = context.filesDir.resolve("orion_extension_test_receipts.json")
        file.writeText("{ broken")
        val store = ExtensionTestReceiptStore(context)
        assertNull(runCatching {
            store.get("format_helper", SelfExtendingExtensionPolicy.sha256("source"))
        }.getOrNull())
        assertTrue(file.readText().contains("broken"))
    }
}
