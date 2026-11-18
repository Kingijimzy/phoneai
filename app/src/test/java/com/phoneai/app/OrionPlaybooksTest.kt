package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPlaybooksTest {
    @Test fun everyPlaybookIsPresentAndBoundedInSize() {
        assertEquals(listOf("build-platform", "debugging", "tdd", "review", "github-flow", "inspect"), OrionPlaybooks.names())
        OrionPlaybooks.names().forEach { assertTrue(it, OrionPlaybooks.body(it)!!.length in 300..3500) }
    }

    @Test fun lookupIsForgivingAndUnknownNamesListWhatExists() {
        assertNotNull(OrionPlaybooks.body(" TDD "))
        assertNull(OrionPlaybooks.body("nope"))
        assertTrue(OrionPlaybooks.run("nope").contains("build-platform"))
        assertTrue(OrionPlaybooks.run("").contains("debugging"))
    }

    @Test fun buildPlaybookCoversShippingThroughCi() {
        val b = OrionPlaybooks.body("build-platform")!!
        assertTrue(b.contains("CI") && b.contains("APK") && b.contains("PLAN.md"))
    }

    @Test fun androidScaffoldBuildsAnApkInCi() {
        val s = OrionDevspace.scaffold("demoapp", "android")!!
        assertTrue(s.contains("com.orion.demoapp"))
        assertTrue(s.contains("assembleDebug") && s.contains("upload-artifact") && s.contains("AndroidManifest.xml"))
        assertNull(OrionDevspace.scaffold("bad name", "android"))
    }

    @Test fun devspaceExposesPlaybooks() {
        assertTrue(ToolRegistry.find("devspace")!!.promptLine.contains("playbook"))
    }

    @Test fun githubBlobLinksBecomeRawLinks() {
        assertEquals("https://raw.githubusercontent.com/o/r/main/skills/x/SKILL.md", OrionPlaybooks.rawUrl("https://github.com/o/r/blob/main/skills/x/SKILL.md"))
    }

    @Test fun skillFrontMatterIsSplitOff() {
        val (d, b) = OrionPlaybooks.parseSkill("---\nname: x\ndescription: \"Find the root cause\"\n---\n\n# Body\nsteps")
        assertEquals("Find the root cause", d)
        assertEquals("# Body\nsteps", b)
        assertEquals("" to "plain text", OrionPlaybooks.parseSkill("plain text"))
    }
}
