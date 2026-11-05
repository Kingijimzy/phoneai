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
class ExtensionVersionHistoryTest {
    private lateinit var context: Context
    private lateinit var registry: SelfExtendingExtensionStore
    private lateinit var history: ExtensionVersionHistory
    private lateinit var receipts: ExtensionTestReceiptStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "orion_generated_extensions.json",
            "orion_extension_versions.json",
            "orion_extension_test_receipts.json"
        ).forEach { context.filesDir.resolve(it).delete() }
        registry = SelfExtendingExtensionStore(context)
        history = ExtensionVersionHistory(context)
        receipts = ExtensionTestReceiptStore(context)
    }

    private fun draft(source: String): GeneratedExtension {
        val hash = SelfExtendingExtensionPolicy.sha256(source)
        return GeneratedExtension("format_helper", "Format helper", "python", source, hash, "test", 1L)
    }

    private fun activate(source: String) {
        val extension = draft(source)
        assertTrue(registry.saveDraft(extension))
        val report = ExtensionTestReport(true, extension.sourceSha256, "passed")
        assertTrue(receipts.record(extension.id, report))
        assertTrue(registry.promote(extension.id, report, userApproved = true))
        assertTrue(history.record(extension.copy(enabled = true, verified = true)))
    }

    @Test fun activatedVersionsAreImmutableAndOrdered() {
        activate("RESULT = INPUT")
        val first = history.all("format_helper").single()
        assertEquals(1, first.version)
        assertEquals(SelfExtendingExtensionPolicy.sha256("RESULT = INPUT"), first.sourceSha256)
    }

    @Test fun rollbackRequiresPersistedPassingReceiptForExactArchivedHash() {
        activate("RESULT = INPUT")
        val firstHash = registry.enabled("format_helper")!!.sourceSha256
        assertTrue(registry.disable("format_helper"))
        val second = draft("RESULT = INPUT + 1")
        assertTrue(registry.saveRevision(second))
        val secondReport = ExtensionTestReport(true, second.sourceSha256, "passed")
        assertTrue(receipts.record(second.id, secondReport))
        assertNotNull(receipts.get("format_helper", firstHash))
        assertTrue(registry.promote(second.id, secondReport, userApproved = true))
        assertTrue(history.record(second.copy(enabled = true, verified = true)))

        val previous = history.previous("format_helper", second.sourceSha256)
        assertNotNull(previous)
        assertEquals(firstHash, previous!!.sourceSha256)
        val exactReceipt = receipts.get("format_helper", previous.sourceSha256)
        assertNotNull(exactReceipt)
        assertTrue(registry.activateVersion(previous, exactReceipt!!, approved = true))
        assertEquals(firstHash, registry.enabled("format_helper")!!.sourceSha256)
    }

    @Test fun modifiedOrUntestedArchivedSourceCannotBeActivated() {
        activate("RESULT = INPUT")
        val archived = history.all("format_helper").single()
        val wrong = ExtensionTestReport(true, SelfExtendingExtensionPolicy.sha256("different"), "passed")
        assertFalse(registry.activateVersion(archived, wrong, approved = true))
    }

    @Test fun corruptHistoryFailsClosedWithoutInventingVersions() {
        context.filesDir.resolve("orion_extension_versions.json").writeText("{broken")
        assertNull(history.previous("format_helper", "active"))
        assertTrue(runCatching { history.all("format_helper") }.isFailure)
    }
}
