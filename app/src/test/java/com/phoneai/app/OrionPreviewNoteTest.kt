package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrionPreviewNoteTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun page(root: File, name: String): File =
        File(root, "orion-python/workspace/$name").apply { parentFile!!.mkdirs(); writeText("<h1>hi</h1>") }

    @Test fun builtPageBecomesAPreviewMarker() {
        val root = tmp.newFolder()
        val f = page(root, "site/index.html")
        val note = OrionPreviewNote.build(root, "workspace_write", JSONObject().put("path", "site/index.html"), false)!!
        assertTrue(note.startsWith("[PREVIEW]\n" + f.absolutePath))
        val content = OrionMessageContent.fromLegacy("note", note)
        assertTrue(content.blocks.single() is OrionMessageBlock.Preview)
    }

    @Test fun nothingForFailuresOtherFilesOrMissingPages() {
        val root = tmp.newFolder()
        page(root, "a.html")
        assertNull(OrionPreviewNote.build(root, "workspace_write", JSONObject().put("path", "a.html"), true))
        assertNull(OrionPreviewNote.build(root, "workspace_write", JSONObject().put("path", "a.py"), false))
        assertNull(OrionPreviewNote.build(root, "workspace_write", JSONObject().put("path", "nope.html"), false))
        assertNull(OrionPreviewNote.build(root, "shell", JSONObject().put("path", "a.html"), false))
    }

    @Test fun previewSurvivesSaveAndLoad() {
        val block = OrionMessageBlock.Preview("[PREVIEW]\n/x/a.html\na.html")
        val json = OrionMessageContent(blocks = listOf(block)).toJson()
        assertEquals(listOf(block), OrionMessageContent.fromJson(json).blocks)
    }
}

class ForgedToolSelectionTest {
    @Test fun neverWorkingForgedToolIsRetiredAndWorkingOnesStay() {
        val stats = mapOf("ext_bad" to Triple(0, 3, 10L), "ext_ok" to Triple(4, 3, 10L), "ext_new" to Triple(0, 1, 5L))
        assertTrue(OrionForgedTools.isRetired("ext_bad", stats))
        assertEquals(false, OrionForgedTools.isRetired("ext_ok", stats))
        assertEquals(false, OrionForgedTools.isRetired("ext_new", stats))
        assertEquals(false, OrionForgedTools.isRetired("ext_unknown", stats))
    }
}

class CardPersistenceTest {
    @Test fun resultCardsAreKeptButChatterNotesAreNot() {
        val p = { k: String, t: String -> OrionConversationRepository.persistable(k, t) }
        assertTrue(p("me", "hi") && p("ai", "yo"))
        assertTrue(p("note", "[PREVIEW]\n/x/a.html\na"))
        assertTrue(p("note", "[IMAGE]\n/x/a.png\ncap"))
        assertTrue(p("note", "[SHELL:RESULT]\n\$ ls"))
        assertEquals(false, p("note", "thinking about it"))
        assertEquals(false, p("ai", "  "))
    }
}
