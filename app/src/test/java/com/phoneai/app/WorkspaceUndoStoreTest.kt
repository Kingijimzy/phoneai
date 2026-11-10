package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceUndoStoreTest {
    private lateinit var context: Context
    private lateinit var workspace: OrionPythonWorkspace
    private lateinit var undo: WorkspaceUndoStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "orion-python/workspace").deleteRecursively()
        File(context.filesDir, "orion_workspace_undo.json").delete()
        workspace = OrionPythonWorkspace(context)
        undo = WorkspaceUndoStore(context)
    }

    @Test fun undoCreatedFileRemovesOnlyTheExactRecordedContent() {
        assertTrue(workspace.write("created.txt", "first").startsWith("saved "))
        assertTrue(undo.recordWrite("created.txt", null, "first"))
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())
        assertNull(workspace.readForUndo("created.txt"))
    }

    @Test fun undoOverwriteRestoresPreviousContents() {
        assertTrue(workspace.write("notes.txt", "before").startsWith("saved "))
        assertTrue(workspace.write("notes.txt", "after").startsWith("saved "))
        assertTrue(undo.recordWrite("notes.txt", "before", "after"))
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())
        assertEquals("before", workspace.readForUndo("notes.txt"))
    }

    @Test fun undoDeleteRestoresDeletedFile() {
        assertTrue(workspace.write("delete-me.txt", "preserve me").startsWith("saved "))
        assertTrue(workspace.delete("delete-me.txt"))
        assertTrue(undo.recordDelete("delete-me.txt", "preserve me"))
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())
        assertEquals("preserve me", workspace.readForUndo("delete-me.txt"))
    }

    @Test fun undoMoveMovesTheFileBackWithoutOverwriting() {
        assertTrue(workspace.write("from.txt", "move me").startsWith("saved "))
        assertTrue(workspace.move("from.txt", "nested/to.txt").startsWith("moved "))
        assertTrue(undo.recordMove("from.txt", "nested/to.txt", "move me"))
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())
        assertEquals("move me", workspace.readForUndo("from.txt"))
        assertNull(workspace.readForUndo("nested/to.txt"))
    }

    @Test fun changedFileIsNeverOverwrittenByUndo() {
        assertTrue(workspace.write("notes.txt", "before").startsWith("saved "))
        assertTrue(workspace.write("notes.txt", "after").startsWith("saved "))
        assertTrue(undo.recordWrite("notes.txt", "before", "after"))
        assertTrue(workspace.write("notes.txt", "owner edit").startsWith("saved "))
        val result = undo.undoLast().orEmpty()
        assertTrue(result.contains("no data was overwritten", ignoreCase = true))
        assertEquals("owner edit", workspace.readForUndo("notes.txt"))
    }

    @Test fun destinationCollisionPreventsMoveAndUndoOverwrite() {
        assertTrue(workspace.write("from.txt", "source").startsWith("saved "))
        assertTrue(workspace.write("to.txt", "destination").startsWith("saved "))
        assertTrue(workspace.move("from.txt", "to.txt").startsWith("error:"))
        assertEquals("source", workspace.readForUndo("from.txt"))
        assertEquals("destination", workspace.readForUndo("to.txt"))
    }

    @Test fun corruptJournalIsNotMistakenForAnEmptyUndoHistory() {
        File(context.filesDir, "orion_workspace_undo.json").writeText("not-json")
        assertEquals(Long.MAX_VALUE, undo.latestTimestamp())
        assertTrue(undo.undoLast().orEmpty().contains("history is unreadable"))
    }

    @Test fun oversizedSnapshotsAreRejected() {
        assertFalse(WorkspaceUndoStore.isSnapshotSizeAllowed(16L * 1024 * 1024 + 1))
        assertTrue(WorkspaceUndoStore.isSnapshotSizeAllowed(16L * 1024 * 1024))
    }

    @Test fun deletedFileGoesToTrashInsteadOfBeingPermanentlyRemoved() {
        assertTrue(workspace.write("trash-me.txt", "to be recycled").startsWith("saved "))
        assertTrue(workspace.delete("trash-me.txt"))
        assertNull(workspace.readForUndo("trash-me.txt"))
        val listing = workspace.listTrash()
        assertTrue(listing.contains("trash-me.txt"))
        val restored = workspace.restoreFromTrash("trash-me.txt")
        assertTrue(restored.startsWith("restored "))
        assertEquals("to be recycled", workspace.readForUndo("trash-me.txt"))
        // clean up: delete again to leave workspace clean
        workspace.delete("trash-me.txt")
    }

    @Test fun restoreFromTrashReturnsFileToOriginalPath() {
        assertTrue(workspace.write("restore-me.txt", "original content").startsWith("saved "))
        assertTrue(workspace.delete("restore-me.txt"))
        assertNull(workspace.readForUndo("restore-me.txt"))
        val result = workspace.restoreFromTrash("restore-me.txt")
        assertTrue(result.startsWith("restored "))
        assertEquals("original content", workspace.readForUndo("restore-me.txt"))
    }

    @Test fun restoreFromTrashFailsWhenDestinationExists() {
        assertTrue(workspace.write("already-there.txt", "exists").startsWith("saved "))
        assertTrue(workspace.write("trashed.txt", "trashed content").startsWith("saved "))
        assertTrue(workspace.delete("trashed.txt"))
        val result = workspace.restoreFromTrash("already-there.txt")
        assertTrue(result.startsWith("error:"))
    }

    @Test fun emptyTrashPermanentlyRemovesAllTrashedFiles() {
        assertTrue(workspace.write("t1.txt", "one").startsWith("saved "))
        assertTrue(workspace.write("t2.txt", "two").startsWith("saved "))
        assertTrue(workspace.delete("t1.txt"))
        assertTrue(workspace.delete("t2.txt"))
        assertEquals(2, workspace.trashCount())
        assertEquals(2, workspace.emptyTrash())
        assertEquals(0, workspace.trashCount())
        assertTrue(workspace.listTrash().contains("(trash empty)"))
    }

    @Test fun interruptedWritePartFilesAreCleanedUp() {
        assertTrue(workspace.write("clean.txt", "clean content").startsWith("saved "))
        val partFile = File(context.filesDir, "orion-python/workspace/.orion-clean.txt.part")
        partFile.writeText("partial garbage from interrupted write")
        assertTrue(partFile.exists())
        assertEquals(1, workspace.cleanupInterruptedWrites())
        assertFalse(partFile.exists())
    }

    @Test fun overwriteUndoRestoresOriginalContentsExactlyAcceptanceTest() {
        // Acceptance test: Orion overwrites a file, reverses the operation and restores the original contents exactly.
        val path = "acceptance.txt"
        val original = "This is the original file content.\nIt has multiple lines.\nAnd important data."
        val overwrite = "This is the overwritten content.\nIt replaces the original."

        // Step 1: Create the file with original content
        assertTrue(workspace.write(path, original).startsWith("saved "))
        assertEquals(original, workspace.readForUndo(path))

        // Step 2: Record the write (simulating Orion's file-operation transaction record)
        assertTrue(undo.recordWrite(path, null, original))

        // Step 3: Overwrite the file
        assertTrue(workspace.write(path, overwrite).startsWith("saved "))
        assertEquals(overwrite, workspace.readForUndo(path))

        // Step 4: Record the overwrite (before = original, after = overwritten)
        assertTrue(undo.recordWrite(path, original, overwrite))

        // Step 5: Undo the overwrite
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())

        // Step 6: Verify original contents are restored exactly
        assertEquals(original, workspace.readForUndo(path))
        assertNotEquals(overwrite, workspace.readForUndo(path))
    }

    @Test fun deleteThenUndoRestoresFromTrash() {
        val path = "trash-undo.txt"
        val content = "content to be deleted and restored"
        assertTrue(workspace.write(path, content).startsWith("saved "))
        assertTrue(workspace.delete(path)) // moves to trash
        assertNull(workspace.readForUndo(path))

        // Record the delete with trash path
        assertTrue(undo.recordDelete(path, content, path))

        // Undo should restore from trash
        assertEquals("Undid the last workspace file change successfully.", undo.undoLast())
        assertEquals(content, workspace.readForUndo(path))
    }
}
