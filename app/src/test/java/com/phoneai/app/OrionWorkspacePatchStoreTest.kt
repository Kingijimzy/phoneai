package com.phoneai.app

import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionWorkspacePatchStoreTest {
    private fun context(): Context = RuntimeEnvironment.getApplication<Context>().also { c ->
        java.io.File(c.filesDir, "orion_patch_proposals").deleteRecursively()
    }

    @Test fun stagesPatchWithoutMutatingWorkspaceAndPersistsReviewHashes() {
        val c = context()
        val source = "fun value() = 1\n".toByteArray()
        val replacement = "fun value() = 2\n".toByteArray()
        val proposal = OrionWorkspacePatchStore.stage(c, Uri.parse("content://workspace/tree/root"),
            "Sample.kt", source, replacement)
        val row = org.json.JSONObject(java.io.File(c.filesDir,
            "orion_patch_proposals/${proposal.id}.json").readText())
        assertEquals("pending_owner_approval", row.optString("status"))
        assertEquals(proposal.sourceSha256, row.optString("sourceSha256"))
        assertEquals(proposal.replacementSha256, row.optString("replacementSha256"))
        assertArrayEquals(source, android.util.Base64.decode(row.getString("sourceBase64"), android.util.Base64.NO_WRAP))
        assertArrayEquals(replacement, android.util.Base64.decode(row.getString("replacementBase64"), android.util.Base64.NO_WRAP))
    }

    @Test fun rejectsTraversalBinaryAndOversizedPatchInputs() {
        val c = context()
        val source = "safe".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            OrionWorkspacePatchStore.stage(c, Uri.parse("content://workspace/tree/root"), "../secrets", source, source)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionWorkspacePatchStore.stage(c, Uri.parse("content://workspace/tree/root"), "Safe.kt", byteArrayOf(0), source)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionWorkspacePatchStore.stage(c, Uri.parse("content://workspace/tree/root"), "Safe.kt", ByteArray(80_001), source)
        }
    }
}
