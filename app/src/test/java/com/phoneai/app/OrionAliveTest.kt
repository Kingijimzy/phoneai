package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class OrionAliveTest {
    private lateinit var c: Context

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("orion_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun ledgerRemembersScriptsAndFindsThemByKeyword() {
        OrionActionLedger.record(c, "script", "python_exec", true, "python3 backup_photos.py --dest /sdcard/backup")
        OrionActionLedger.record(c, "script", "shell", false, "curl https://example.com/data")
        val hits = OrionActionLedger.recent(c, "run the photos backup again")
        assertEquals(1, hits.size)
        assertTrue(hits[0].summary.contains("backup_photos"))
        assertTrue(OrionActionLedger.section(c, "photos backup").startsWith("PAST ACTIONS"))
        assertEquals("", OrionActionLedger.section(c, "hello"))
    }

    @Test fun ledgerScrubsSecretsFromSummaries() {
        OrionActionLedger.record(c, "script", "shell", true, "curl -H 'Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789' https://api")
        assertFalse(OrionActionLedger.recent(c, "curl Authorization api").joinToString { it.summary }.contains("ghp_abcdefghijklmnop"))
    }

    @Test fun forgeJudgeCountsOnlySinceTheLatestForge() {
        OrionActionLedger.noteForge(c, "calc")
        repeat(3) { OrionActionLedger.noteRun(c, "calc", false) }
        assertTrue(OrionActionLedger.isRetired(c, "calc"))
        assertEquals(OrionActionLedger.Judgement.FAILING, OrionActionLedger.judge(0, 3))
        OrionActionLedger.noteForge(c, "calc")   // rebuilt: fresh chance
        assertFalse(OrionActionLedger.isRetired(c, "calc"))
        repeat(2) { OrionActionLedger.noteRun(c, "calc", true) }
        val (o, f) = OrionActionLedger.sinceForge(c, "calc")
        assertEquals(OrionActionLedger.Judgement.WORKED, OrionActionLedger.judge(o, f))
        assertEquals(OrionActionLedger.Judgement.UNTESTED, OrionActionLedger.judge(0, 0))
        assertEquals(OrionActionLedger.Judgement.MIXED, OrionActionLedger.judge(1, 1))
    }

    @Test fun afterCallLogsScriptsAndTracksForgedRuns() {
        OrionActionLedger.afterCall(c, "python_exec", "python_exec", JSONObject().put("code", "print(1)"), "1")
        OrionActionLedger.afterCall(c, "extension_forge", "extension_forge", JSONObject().put("id", "calc"), "ok: forged and enabled")
        OrionActionLedger.afterCall(c, "ext_calc", "extension_run", JSONObject().put("id", "calc"), "error: boom")
        assertEquals(0 to 1, OrionActionLedger.sinceForge(c, "calc"))
        OrionActionLedger.afterCall(c, "recall", "recall", JSONObject(), "x")
        assertTrue(OrionActionLedger.recent(c, "print forged calc python_exec", 10).size >= 2)
    }

    @Test fun vaultStoresWhereNeverTheSecret() {
        assertTrue(OrionActionLedger.vaultAdd(c, "pi ssh key", "workspace/.ssh/id_ed25519 on devspace").startsWith("ok"))
        assertTrue(OrionActionLedger.vaultAdd(c, "github", "ghp_abcdefghijklmnopqrstuvwxyz0123456789").startsWith("error"))
        assertTrue(OrionActionLedger.vaultAdd(c, "k", "-----BEGIN OPENSSH PRIVATE KEY-----").startsWith("error"))
        assertEquals(listOf("pi ssh key"), OrionActionLedger.vault(c).map { it.first })
    }

    @Test fun correctionsBecomeStandingLinesInHisWords() {
        assertNull(OrionCorrections.detect("good morning orion"))
        assertEquals("no, shorter please", OrionCorrections.detect("no, shorter please"))
        assertTrue(OrionCorrections.learn(c, "Don't open the browser unless I say so"))
        assertFalse(OrionCorrections.learn(c, "don't open the browser unless I say so"))
        assertTrue(OrionMindStore.rulesBlock(c).contains("LEARNED FROM HIS CORRECTIONS"))
        OrionCorrections.setLearned(c, "")
        assertEquals("", OrionMindStore.rulesBlock(c))
    }

    @Test fun everyKindOfWorkGetsItsOwnMotion() {
        val k = OrionTaskMotion::kindFor
        assertEquals(OrionTaskMotion.Kind.CODE, k("python_exec"))
        assertEquals(OrionTaskMotion.Kind.CODE, k("ext_calc"))
        assertEquals(OrionTaskMotion.Kind.WEB, k("live_web_search"))
        assertEquals(OrionTaskMotion.Kind.LINK, k("a2a"))
        assertEquals(OrionTaskMotion.Kind.MEMORY, k("recall"))
        assertEquals(OrionTaskMotion.Kind.MUSIC, k("play_media"))
        assertEquals(OrionTaskMotion.Kind.DEVICE, k("open_app"))
        assertEquals(OrionTaskMotion.Kind.THINK, k("something_else"))
        assertEquals(OrionTaskMotion.Kind.WEB, OrionTaskMotion.kindForPhase("Searching the web"))
        assertEquals(OrionTaskMotion.Kind.CODE, OrionTaskMotion.kindForPhase("Building the solution"))
        assertEquals(7, OrionTaskMotion.Kind.values().map { OrionTaskMotion.label(it) }.toSet().size)
    }
}
