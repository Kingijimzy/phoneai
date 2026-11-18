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
}
