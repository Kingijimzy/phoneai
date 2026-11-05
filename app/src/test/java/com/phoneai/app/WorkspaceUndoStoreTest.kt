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
        assertTrue(result.contains("no data was overwritten"))
        assertEquals("owner edit", workspace.readForUndo("notes.txt"))
    }

    @Test fun destinationCollisionPreventsMoveAndUndoOverwrite() {
        assertTrue(workspace.write("from.txt", "source").startsWith("saved "))
        assertTrue(workspace.write("to.txt", "destination").startsWith("saved "))
        assertTrue(workspace.move("from.txt", "to.txt").startsWith("error:"))
        assertEquals("source", workspace.readForUndo("from.txt"))
        assertEquals("destination", workspace.readForUndo("to.txt"))
    }

    @Test fun oversizedSnapshotsAreRejected() {
        val tooLarge = "x".repeat(OrionPythonWorkspace.MAX_FILE_BYTES + 1)
        assertFalse(undo.recordWrite("large.txt", null, tooLarge))
    }
}
