package com.phoneai.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodeSpaceModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pathsCannotEscapeTheRoot() {
        val root = tmp.newFolder("ws")
        assertNotNull(CodeSpaceModel.resolve(root, "src/a.py"))
        assertNull(CodeSpaceModel.resolve(root, "../secret"))
        assertNull(CodeSpaceModel.resolve(root, "a/../../x"))
    }

    @Test fun listingPutsFoldersFirstAndHidesNoise() {
        val d = tmp.newFolder("p")
        File(d, "b.txt").writeText("x"); File(d, "A.md").writeText("x")
        File(d, "zdir").mkdir(); File(d, ".git").mkdir(); File(d, "node_modules").mkdir()
        assertEquals(listOf("zdir", "A.md", "b.txt"), CodeSpaceModel.list(d).map { it.name })
    }

    @Test fun copyNeverOverwritesOrNestsAFolderInItself() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        File(a, "f.txt").writeText("one"); File(b, "f.txt").writeText("keep")
        assertTrue(CodeSpaceModel.copyInto(File(a, "f.txt"), b).startsWith("Copied"))
        assertEquals("keep", File(b, "f.txt").readText())
        assertEquals("one", File(b, "f (2).txt").readText())
        val inner = File(a, "inner").also { it.mkdir() }
        assertTrue(CodeSpaceModel.copyInto(a, inner).contains("itself"))
    }

    @Test fun nameAndRunnerRules() {
        assertTrue(CodeSpaceModel.validNewName("main.py"))
        assertFalse(CodeSpaceModel.validNewName("../x")); assertFalse(CodeSpaceModel.validNewName(" "))
        assertEquals("python3 'a.py'", CodeSpaceModel.runCommandFor("a.py"))
        assertNull(CodeSpaceModel.runCommandFor("notes.md"))
    }

    @Test fun colouringKeepsTheTextIntact() {
        val code = "def f():\n    return 'x'  # hi\n"
        val a = CodeSpaceModel.annotate("python", code)
        assertEquals(code, a.text)
        assertTrue(a.spanStyles.isNotEmpty())
    }

    @Test fun filesGetTypedIconsSizesAndDates() {
        assertEquals(CodeSpaceModel.Kind.FOLDER, CodeSpaceModel.kindOf("src", true))
        assertEquals(CodeSpaceModel.Kind.PYTHON, CodeSpaceModel.kindOf("app.PY", false))
        assertEquals(CodeSpaceModel.Kind.JSON, CodeSpaceModel.kindOf("plugin.json", false))
        assertEquals(CodeSpaceModel.Kind.IMAGE, CodeSpaceModel.kindOf("a.png", false))
        assertEquals(CodeSpaceModel.Kind.OTHER, CodeSpaceModel.kindOf("README", false))
        assertEquals("512 B", CodeSpaceModel.formatSize(512))
        assertEquals("2 KB", CodeSpaceModel.formatSize(2048))
        assertEquals("1.5 MB", CodeSpaceModel.formatSize(1_572_864))
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_794_000_000_000L // Nov 2026
        assertEquals("", CodeSpaceModel.formatDate(0, now, utc))
        assertTrue(CodeSpaceModel.formatDate(now - 86_400_000L, now, utc).contains(","))
        assertTrue(CodeSpaceModel.formatDate(now - 400L * 86_400_000L, now, utc).contains("2025"))
    }

    @Test fun listingCarriesModifiedTime() {
        val d = tmp.newFolder("m")
        File(d, "a.txt").writeText("x")
        assertTrue(CodeSpaceModel.list(d).single().modified > 0L)
    }

    @Test fun breadcrumbsWalkFromTheRootDown() {
        val root = tmp.newFolder("bc")
        val deep = File(root, "projects/phoneai").also { it.mkdirs() }
        assertEquals(listOf("Workspace", "projects", "phoneai"), CodeSpaceModel.breadcrumbs(root, deep, "Workspace").map { it.first })
        assertEquals(listOf("Workspace"), CodeSpaceModel.breadcrumbs(root, root, "Workspace").map { it.first })
        assertEquals(deep.canonicalFile, CodeSpaceModel.breadcrumbs(root, deep, "Workspace").last().second)
    }
}
