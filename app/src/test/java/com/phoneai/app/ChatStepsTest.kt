package com.phoneai.app

import com.phoneai.app.ChatMood.Mood
import org.junit.Assert.*
import org.junit.Test

class ChatStepsTest {
    private val sec = 1_000_000_000L
    private fun note(id: Long, text: String) = StepLine(id, "note", text)

    @Test fun durationFormatCoversSecondsMinutesAndHours() {
        assertEquals("0s", StepFormat.duration(0, false))
        assertEquals("3s", StepFormat.duration(3, false))
        assertEquals("59s", StepFormat.duration(59, false))
        assertEquals("1m", StepFormat.duration(60, false))
        assertEquals("2m30s", StepFormat.duration(150, false))
        assertEquals("1h", StepFormat.duration(3600, false))
        assertEquals("1h12m", StepFormat.duration(4320, false))
    }

    @Test fun runningStepsGetAnEllipsisAndNegativesClamp() {
        assertEquals("12s…", StepFormat.duration(12, true))
        assertEquals("0s", StepFormat.duration(-5, false))
    }

    @Test fun labelsAreHumanForCommonToolsAndSentencesForTheRest() {
        assertEquals("Run: ls -la", StepFormat.label("shell", "ls -la"))
        assertEquals("Run a command", StepFormat.label("shell", null))
        assertEquals("Open apkvision.org", StepFormat.label("browser_open", "https://www.apkvision.org/pes?x=1"))
        assertEquals("Screenshot apkvision.org", StepFormat.label("browser_screenshot", "https://apkvision.org"))
        assertEquals("Read screen", StepFormat.label("read_screen"))
        assertEquals("Step", StepFormat.label(" "))
    }

    @Test fun longCommandsAreTruncatedInTheLabel() {
        val label = StepFormat.label("shell", "x".repeat(80))
        assertTrue(label.length <= "Run: ".length + 41)
        assertTrue(label.endsWith("…"))
    }

    @Test fun runningNoteThenResultNoteBecomeOneFinishedStepWithItsDuration() {
        val steps = StepTracker.derive(listOf(
            note(10 * sec, "[TASK:RUNNING]\nRunning read_screen"),
            note(13 * sec, "[TASK:RESULT]\nread_screen — 12 elements")
        ))
        assertEquals(1, steps.size)
        assertEquals(StepStatus.SUCCESS, steps[0].status)
        assertEquals("3s", steps[0].durationLabel(99 * sec))
    }

    @Test fun failedShellStepKeepsItsCommandInTheLabel() {
        val steps = StepTracker.derive(listOf(
            note(5 * sec, "[TASK:RUNNING]\nRunning shell"),
            note(25 * sec, "[SHELL:FAILED]\n\$ npm test\nerror: exit code 1")
        ))
        assertEquals(StepStatus.FAILED, steps[0].status)
        assertEquals("Run: npm test", steps[0].label)
        assertEquals("20s", steps[0].durationLabel(0))
    }

    @Test fun browserResultUsesTheToolAndHost() {
        val steps = StepTracker.derive(listOf(
            note(1 * sec, "[TASK:RUNNING]\nRunning browser_open"),
            note(2 * sec, "[BROWSER:RESULT]\nURL: https://apkvision.org/\nTOOL: browser_open\nRESULT: ok")
        ))
        assertEquals("Open apkvision.org", steps[0].label)
        assertEquals(StepStatus.SUCCESS, steps[0].status)
    }

    @Test fun anUnfinishedStepStaysRunningAndShowsElapsedTime() {
        val steps = StepTracker.derive(listOf(note(10 * sec, "[TASK:RUNNING]\nRunning shell")))
        assertTrue(steps[0].active)
        assertEquals("5s…", steps[0].durationLabel(15 * sec))
    }

    @Test fun aNewStepInterruptsAnEarlierUnfinishedOne() {
        val steps = StepTracker.derive(listOf(
            note(1 * sec, "[TASK:RUNNING]\nRunning a"),
            note(2 * sec, "[TASK:RUNNING]\nRunning b")
        ))
        assertEquals(StepStatus.CANCELLED, steps[0].status)
        assertNull("a cancelled step shows no duration", steps[0].durationLabel(9 * sec))
        assertTrue(steps[1].active)
    }

    @Test fun restoredHistoryWithTinyIdGapsShowsNoDuration() {
        val base = 7_000_000_000_000L
        val steps = StepTracker.derive(listOf(
            note(base, "[TASK:RUNNING]\nRunning read_screen"),
            note(base + 1, "[TASK:RESULT]\nread_screen — ok")
        ))
        assertNull(steps[0].durationLabel(0))
    }

    @Test fun resultWithoutAStartNoteStillMakesAFinishedStep() {
        val steps = StepTracker.derive(listOf(note(50 * sec, "[TASK:FAILED]\nclick — error: no element")))
        assertEquals(1, steps.size)
        assertEquals(StepStatus.FAILED, steps[0].status)
        assertNull(steps[0].durationLabel(0))
    }

    @Test fun nonNoteLinesAndUnknownNotesAreIgnored() {
        val steps = StepTracker.derive(listOf(
            StepLine(1, "me", "[TASK:RUNNING]\nRunning x"),
            note(2, "just a message"),
            note(3, "[TASK:RUNNING]\n")
        ))
        assertTrue(steps.isEmpty())
    }

    @Test fun pagerFollowsTheNewestStepUnlessTheCurrentOneIsStillRunning() {
        val done = ToolStep(1, "a", "A", StepStatus.SUCCESS, 1)
        val running = ToolStep(2, "b", "B", StepStatus.RUNNING, 2)
        val newest = ToolStep(3, "c", "C", StepStatus.SUCCESS, 3)
        assertEquals(2, StepPager.follow(0, listOf(done, running, newest)))
        assertEquals(1, StepPager.follow(1, listOf(done, running)))
        assertEquals(0, StepPager.follow(5, emptyList()))
    }

    @Test fun pagerLabelAndArrowsAppearOnlyForMoreThanOneStep() {
        assertNull(StepPager.label(0, 1))
        assertFalse(StepPager.showArrows(1))
        assertEquals("26 / 26", StepPager.label(25, 26))
        assertTrue(StepPager.showArrows(2))
        assertEquals(0, StepPager.previous(0))
        assertEquals(4, StepPager.next(4, 5))
        assertEquals(0, StepPager.next(0, 0))
    }

    @Test fun moodPrioritiesAndFading() {
        assertEquals(Mood.WAITING, ChatMood.moodFor(true, true, null, null))
        assertEquals(Mood.WORKING, ChatMood.moodFor(true, false, 1000, 1000))
        assertEquals(Mood.ERROR, ChatMood.moodFor(false, false, 1000, 1000))
        assertEquals(Mood.HAPPY, ChatMood.moodFor(false, false, 1000, null))
        assertEquals("a finish older than 3 s fades to idle", Mood.IDLE, ChatMood.moodFor(false, false, ChatMood.HAPPY_MS, null))
        assertEquals("an error older than 4 s fades", Mood.IDLE, ChatMood.moodFor(false, false, null, ChatMood.ERROR_MS))
    }

    @Test fun statusLineNamesTheCurrentStep() {
        assertEquals("Run: ls", ChatMood.statusLine(Mood.WORKING, "Run: ls", false))
        assertEquals("Thinking", ChatMood.statusLine(Mood.WORKING, null, true))
        assertEquals("Waiting for you", ChatMood.statusLine(Mood.WAITING, "x", false))
        assertEquals("Ready", ChatMood.statusLine(Mood.IDLE, null, false))
        assertEquals("Done", ChatMood.statusLine(Mood.HAPPY, null, false))
    }
}
