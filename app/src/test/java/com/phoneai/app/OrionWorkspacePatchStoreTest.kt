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
    private fun context(): Context = RuntimeEnvironment.getApplication().also { c ->
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

    @Test fun stagedProposalCanBeInspectedAndListedWithoutApplyingIt() {
        val ctx = context()
        val source = "before".toByteArray()
        val replacement = "after".toByteArray()
        val proposal = OrionWorkspacePatchStore.stage(ctx, Uri.parse("content://workspace/tree/root"),
            "notes.txt", source, replacement)
        val details = OrionWorkspacePatchStore.inspect(ctx, proposal.id)
        assertNotNull(details)
        assertEquals("pending_owner_approval", details!!.status)
        assertArrayEquals(source, details.source)
        assertArrayEquals(replacement, details.replacement)
        assertEquals(listOf(proposal), OrionWorkspacePatchStore.pending(ctx))
    }

    @Test fun rejectIsTerminalAndRemovesProposalFromPendingQueue() {
        val ctx = context()
        val proposal = OrionWorkspacePatchStore.stage(ctx, Uri.parse("content://workspace/tree/root"),
            "notes.txt", "before".toByteArray(), "after".toByteArray())
        assertTrue(OrionWorkspacePatchStore.reject(ctx, proposal.id, "User rejected this change"))
        assertEquals("rejected", OrionWorkspacePatchStore.inspect(ctx, proposal.id)?.status)
        assertEquals("User rejected this change", OrionWorkspacePatchStore.inspect(ctx, proposal.id)?.terminalReason)
        assertTrue(OrionWorkspacePatchStore.pending(ctx).isEmpty())
        assertTrue(OrionWorkspacePatchStore.reject(ctx, proposal.id))
        assertFalse(OrionWorkspacePatchStore.applyApproved(ctx, proposal.id, proposal.sourceSha256, "approved"))
    }

    @Test fun appliedProposalCannotBeRejectedAgain() {
        val ctx = context()
        val proposal = OrionWorkspacePatchStore.stage(ctx, Uri.parse("content://workspace/tree/root"),
            "notes.txt", "before".toByteArray(), "after".toByteArray())
        val file = java.io.File(ctx.filesDir, "orion_patch_proposals/${proposal.id}.json")
        val row = org.json.JSONObject(file.readText()).put("status", "applied")
        file.writeText(row.toString())
        assertFalse(OrionWorkspacePatchStore.reject(ctx, proposal.id))
        assertTrue(OrionWorkspacePatchStore.pending(ctx).isEmpty())
    }

    @Test fun corruptedProposalPayloadIsNotExposedForReview() {
        val ctx = context()
        val proposal = OrionWorkspacePatchStore.stage(ctx, Uri.parse("content://workspace/tree/root"),
            "notes.txt", "before".toByteArray(), "after".toByteArray())
        val file = java.io.File(ctx.filesDir, "orion_patch_proposals/${proposal.id}.json")
        val row = org.json.JSONObject(file.readText()).put("replacementBase64", "dGFtcGVyZWQ=")
        file.writeText(row.toString())
        assertNull(OrionWorkspacePatchStore.inspect(ctx, proposal.id))
        assertTrue(OrionWorkspacePatchStore.pending(ctx).isEmpty())
    }

}
