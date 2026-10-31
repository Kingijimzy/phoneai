package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelfExtendingExtensionsTest {
    private lateinit var context: Context
    private lateinit var store: SelfExtendingExtensionStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("orion_generated_extensions.json").delete()
        store = SelfExtendingExtensionStore(context)
    }

    private fun draft(id: String = "format_helper", source: String = "def run(value):\n    return str(value).strip()") =
        GeneratedExtension(
            id = id,
            displayName = "Format helper",
            runtime = "python",
            source = source,
            sourceSha256 = SelfExtendingExtensionPolicy.sha256(source),
            description = "A bounded formatting helper",
            createdAtMillis = 100L
        )

    @Test fun generatedCodeIsDraftOnlyUntilTestsAndExplicitApproval() {
        val extension = draft()
        assertTrue(store.saveDraft(extension))
        assertNull(store.enabled(extension.id))

        val failed = ExtensionTestReport(false, extension.sourceSha256, "assertion failed")
        assertFalse(store.promote(extension.id, failed, userApproved = true))
        assertFalse(store.promote(extension.id, ExtensionTestReport(true, extension.sourceSha256, "passed"), userApproved = false))
        assertNull(store.enabled(extension.id))

        assertTrue(store.promote(extension.id, ExtensionTestReport(true, extension.sourceSha256, "passed"), userApproved = true))
        assertEquals(extension.source, store.enabled(extension.id)?.source)
    }

    @Test fun changedSourceCannotBePromotedUsingStaleTestReport() {
        val extension = draft()
        assertTrue(store.saveDraft(extension))
        val staleHash = SelfExtendingExtensionPolicy.sha256("different source")
        assertFalse(store.promote(extension.id, ExtensionTestReport(true, staleHash, "passed"), userApproved = true))
        assertNull(store.enabled(extension.id))
    }

    @Test fun sourceValidationRejectsCommonHostEscapeAndNetworkOperations() {
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("python", "import os\nos.system('id')"))
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("python", "import requests\nrequests.get('https://example.com')"))
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("javascript", "process.exit(1)"))
        assertNull(SelfExtendingExtensionPolicy.validateSource("python", "def run(value):\n    return str(value).strip()"))
    }

    @Test fun duplicateIdsAndUnsupportedRuntimeAreRejected() {
        assertTrue(store.saveDraft(draft()))
        assertFalse(store.saveDraft(draft()))
        assertNotNull(SelfExtendingExtensionPolicy.validateSource("ruby", "puts 'hi'"))
    }

    @Test fun disabledOrTamperedExtensionsAreNeverReturnedAsExecutable() {
        val extension = draft()
        assertTrue(store.saveDraft(extension))
        assertTrue(store.promote(extension.id, ExtensionTestReport(true, extension.sourceSha256, "passed"), userApproved = true))
        assertNotNull(store.enabled(extension.id))
        assertTrue(store.disable(extension.id))
        assertNull(store.enabled(extension.id))
    }

    @Test fun registryCorruptionFailsClosedWithoutOverwritingEvidence() {
        val file = context.filesDir.resolve("orion_generated_extensions.json")
        file.writeText("{ broken")
        assertFalse(store.saveDraft(draft("another_tool")))
        assertTrue(file.readText().contains("broken"))
    }
}
