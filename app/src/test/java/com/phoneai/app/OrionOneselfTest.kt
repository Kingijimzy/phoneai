package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionOneselfTest {
    private lateinit var c: Context

    private fun wipe() {
        listOf("orion_mind", "orion_ledger", "orion_failures").forEach { c.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        OrionMindStore.sync(c)
    }

    @Before fun setUp() { c = RuntimeEnvironment.getApplication(); wipe() }
    @After fun tearDown() { wipe() }

    @Test fun voiceUsesHisOwnWordsOrTheRealActionNeverAStockPhrase() {
        assertEquals("I'll check the shadow script before running it.", OrionVoice.ownWords("I'll check the shadow script before running it. Then more."))
        assertNull(OrionVoice.ownWords("On it."))
        assertNull(OrionVoice.ownWords("Let me think"))
        assertNull(OrionVoice.ownWords(""))
        assertEquals("shell: cd /sdcard/Download/Orion/smc", OrionVoice.action("shell", JSONObject().put("command", "cd /sdcard/Download/Orion/smc")))
        assertEquals("recall", OrionVoice.action("recall", JSONObject()))
        assertEquals("shell: ls", OrionVoice.line("Sure.", "shell", JSONObject().put("command", "ls")))
        assertTrue(OrionVoice.line("Checking whether the key exists first.", "shell", null).startsWith("Checking whether"))
    }

    @Test fun orionCanCreateAndRemoveHisOwnScene() {
        assertEquals(null, OrionScenes.detect("let's do some reading"))
        val r = OrionMindStore.addCustomScene(c, "reading", "do some reading, reading time", "workspace_read, play_media, nonexistent_tool", "Open his book notes and play quiet music.")
        assertTrue(r, r.startsWith("ok"))
        assertEquals("reading", OrionScenes.detect("let's do some reading")?.id)
        assertEquals(listOf("workspace_read", "play_media"), OrionScenes.detect("reading time")!!.tools.filter { it != "nonexistent_tool" })
        assertTrue(OrionScenes.brief("reading time").contains("quiet music"))
        assertTrue(OrionMindStore.addCustomScene(c, "coding", "x y z", "", "no").startsWith("error"))
        assertTrue(OrionMindStore.addCustomScene(c, "bad", "a", "", "brief").startsWith("error"))
        OrionMindStore.removeCustomScene(c, "reading")
        assertNull(OrionScenes.detect("reading time"))
    }

    @Test fun hisOwnNotesAreStoredBoundedAndNeverSecrets() {
        assertTrue(OrionMindStore.addSelfNote(c, "Kingi prefers the result first, then the reasoning.").startsWith("ok"))
        assertTrue(OrionMindStore.addSelfNote(c, "token ghp_abcdefghijklmnopqrstuvwxyz0123456789").startsWith("error"))
        assertTrue(OrionMindStore.addSelfNote(c, "ok").startsWith("error"))
        assertTrue(OrionMindStore.rulesBlock(c).contains("HIS OWN STANDING NOTES"))
        assertTrue(OrionMindStore.rulesBlock(c).contains("result first"))
    }

    @Test fun selfPullsEveryPartIntoOneDescription() {
        OrionCorrections.learn(c, "Don't open the browser unless I say so")
        OrionMindStore.addSelfNote(c, "Always start with the smallest next step.")
        OrionMindStore.addCustomScene(c, "reading", "reading time", "", "Quiet music.")
        OrionFailureNotes.clearForTest(); OrionFailureNotes.record(null, "click_text", "error: no element with text \"Save\"")
        val s = OrionSelf.describe(c, mapOf("recall" to Triple(11, 0, 100L), "click_text" to Triple(1, 39, 100L)))
        assertTrue(s, s.contains("strong at recall"))
        assertTrue(s, s.contains("click_text (error: no element"))
        assertTrue(s, s.contains("Scenes I added: reading"))
        assertTrue(s, s.contains("1 corrections") && s.contains("1 notes"))
        assertNotNull(s)
    }
}
