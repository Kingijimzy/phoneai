package com.phoneai.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrionFolderSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun newFilesAppearOnBothSides() {
        val phone = tmp.newFolder("phone"); val work = tmp.newFolder("work")
        File(phone, "a.txt").writeText("from phone")
        File(work, "sub").mkdirs(); File(work, "sub/b.py").writeText("print(1)")
        val r = OrionFolderSync.syncFolders(phone, work)
        assertEquals(1, r.toWorkspace); assertEquals(1, r.toPhone)
        assertEquals("from phone", File(work, "a.txt").readText())
        assertEquals("print(1)", File(phone, "sub/b.py").readText())
    }

    @Test fun newerCopyWinsAndASecondRunIsQuiet() {
        val phone = tmp.newFolder("p2"); val work = tmp.newFolder("w2")
        val a = File(phone, "n.txt").apply { writeText("old"); setLastModified(1_000_000L) }
        val b = File(work, "n.txt").apply { writeText("newer!"); setLastModified(9_000_000L) }
        OrionFolderSync.syncFolders(phone, work)
        assertEquals("newer!", a.readText())
        assertEquals(0, OrionFolderSync.syncFolders(phone, work).changed)
        assertTrue(b.exists())
    }

    @Test fun deletionsAreNeverPropagatedAndNoiseFoldersAreSkipped() {
        val phone = tmp.newFolder("p3"); val work = tmp.newFolder("w3")
        File(phone, "keep.txt").writeText("k"); File(phone, "node_modules").mkdirs(); File(phone, "node_modules/x.js").writeText("x")
        OrionFolderSync.syncFolders(phone, work)
        File(phone, "keep.txt").delete()
        OrionFolderSync.syncFolders(phone, work)
        assertTrue(File(work, "keep.txt").exists())
        assertTrue(!File(work, "node_modules").exists())
    }

    @Test fun reportReadsAsOneLine() {
        assertEquals("Already in sync", OrionFolderSync.Report(0, 0, 0).line())
        assertTrue(OrionFolderSync.Report(2, 1, 0).line().contains("2 to workspace"))
    }
}
