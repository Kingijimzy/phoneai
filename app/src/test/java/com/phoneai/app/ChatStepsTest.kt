package com.phoneai.app

import com.phoneai.app.ChatMood.Mood
import org.junit.Assert.*
import org.junit.Test

class ChatStepsTest {
    private val sec = 1_000_000_000L
    // Live line ids are System.nanoTime(), far above Int range; small ids model restored (hashed) history.
    private val base = 100 * sec
    private fun note(id: Long, text: String) = StepLine(if (id < 1_000L) id else id + base, "note", text)

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
        assertEquals("5s…", steps[0].durationLabel(115 * sec))
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

    @Test fun hashedRestoredIdsNeverShowADuration() {
        val steps = StepTracker.derive(listOf(
            StepLine(123_456L, "note", "[TASK:RUNNING]\nRunning read_screen"),
            StepLine(2_000_000_000L, "note", "[TASK:RESULT]\nread_screen — ok")
        ))
        assertNull(steps[0].durationLabel(0))
    }

    @Test fun retryNotesAreShownOnTheRunningStep() {
        val steps = StepTracker.derive(listOf(
            note(1 * sec, "[TASK:RUNNING]\nRunning web_search"),
            note(2 * sec, "[TASK:RECOVER]\nRetry 1/2 of read-only 'web_search' after a transient failure; reads have no side effects"),
            note(3 * sec, "[TASK:RECOVER]\nRetry 2/2 of read-only 'web_search' after a transient failure; reads have no side effects"),
            note(5 * sec, "[TASK:RESULT]\nweb_search — ok")
        ))
        assertEquals(2, steps[0].retries)
        assertEquals(StepStatus.SUCCESS, steps[0].status)
    }

    @Test fun cancelNoteMarksTheRunningStepCancelled() {
        val steps = StepTracker.derive(listOf(
            note(1 * sec, "[TASK:RUNNING]\nRunning shell"),
            note(2 * sec, "[TASK:CANCELLED]\nUser stopped execution")
        ))
        assertEquals(StepStatus.CANCELLED, steps[0].status)
    }

    @Test fun taskStateFollowsTheNewestLifecycleNoteOfTheCurrentTask() {
        fun st(vararg l: StepLine) = StepTracker.taskState(l.toList())
        assertEquals(StepTracker.TaskState.NONE, st(StepLine(1, "me", "hi")))
        assertEquals(StepTracker.TaskState.WORKING, st(StepLine(1, "me", "x"), note(2, "[TASK:RUNNING]\nRunning a")))
        assertEquals(StepTracker.TaskState.RECOVERING, st(StepLine(1, "me", "x"), note(2, "[TASK:RECOVER]\nRecovery 1/2")))
        assertEquals(StepTracker.TaskState.WAITING, st(StepLine(1, "me", "x"), note(2, "[TASK:BLOCKED]\nneeds you")))
        assertEquals(StepTracker.TaskState.CANCELLED, st(StepLine(1, "me", "x"), note(2, "[TASK:CANCELLED]\nstopped")))
        assertEquals(StepTracker.TaskState.FAILED, st(StepLine(1, "me", "x"), note(2, "[TASK:FAILED]\na — error")))
        // an earlier task's state never leaks into a new one
        assertEquals(StepTracker.TaskState.NONE, st(note(2, "[TASK:BLOCKED]\nold"), StepLine(3, "me", "new request")))
    }

    @Test fun onlyTheCurrentTasksStepsAreShown() {
        val steps = StepTracker.currentTaskSteps(listOf(
            note(1 * sec, "[TASK:RUNNING]\nRunning old"), note(2 * sec, "[TASK:RESULT]\nold — ok"),
            StepLine(3, "me", "next"),
            note(4 * sec, "[TASK:RUNNING]\nRunning fresh")
        ))
        assertEquals(listOf("fresh"), steps.map { it.tool })
    }

    @Test fun approvalEventsMakeTheTaskWaitThenResume() {
        fun st(vararg l: StepLine) = StepTracker.taskState(l.toList())
        val me = StepLine(1, "me", "x")
        assertEquals(StepTracker.TaskState.APPROVAL, st(me, note(2, "[TASK:RUNNING]\nRunning sms"), note(3, "[TASK:APPROVAL]\nWaiting for your approval")))
        assertEquals(StepTracker.TaskState.WORKING, st(me, note(2, "[TASK:RUNNING]\nRunning sms"), note(3, "[TASK:APPROVAL]\nw"), note(4, "[TASK:APPROVED]\nok")))
        assertEquals(StepTracker.TaskState.WORKING, st(me, note(3, "[TASK:APPROVAL]\nw"), note(4, "[TASK:DENIED]\nno")))
    }

    @Test fun statusTextCoversEveryStepState() {
        fun step(s: StepStatus, retries: Int = 0) = ToolStep(1, "t", "T", s, 1, retries = retries)
        val max = 2
        assertEquals("Running", StepStatusText.of(step(StepStatus.RUNNING), StepTracker.TaskState.WORKING, max))
        assertEquals("Retrying 1/2", StepStatusText.of(step(StepStatus.RUNNING, 1), StepTracker.TaskState.RECOVERING, max))
        assertEquals("Waiting for your approval", StepStatusText.of(step(StepStatus.RUNNING), StepTracker.TaskState.APPROVAL, max))
        assertEquals("Done", StepStatusText.of(step(StepStatus.SUCCESS), StepTracker.TaskState.DONE, max))
        assertEquals("Failed · replanning", StepStatusText.of(step(StepStatus.FAILED), StepTracker.TaskState.RECOVERING, max))
        assertEquals("Failed · waiting for you", StepStatusText.of(step(StepStatus.FAILED), StepTracker.TaskState.WAITING, max))
        assertEquals("Failed", StepStatusText.of(step(StepStatus.FAILED), StepTracker.TaskState.FAILED, max))
        assertEquals("Cancelled", StepStatusText.of(step(StepStatus.CANCELLED), StepTracker.TaskState.CANCELLED, max))
        assertEquals("Stopped", StepStatusText.of(step(StepStatus.CANCELLED), StepTracker.TaskState.WORKING, max))
    }

    @Test fun pagerMovesWithinBoundsAndFollowsNewSteps() {
        assertEquals(0, StepPager.previous(0))
        assertEquals(2, StepPager.next(2, 3))
        assertEquals(1, StepPager.next(0, 3))
        val a = ToolStep(1, "a", "A", StepStatus.SUCCESS, 1)
        val b = ToolStep(2, "b", "B", StepStatus.SUCCESS, 2)
        val c = ToolStep(3, "c", "C", StepStatus.RUNNING, 3)
        assertEquals("a finished step the user browsed back to yields to the new step", 2, StepPager.follow(0, listOf(a, b, c)))
        assertEquals("a running step the user is on is not left", 2, StepPager.follow(2, listOf(a, b, c)))
        assertEquals(0, StepPager.follow(5, emptyList()))
    }

    @Test fun switchingConversationsNeverMixesTasks() {
        val convA = listOf(StepLine(1, "me", "a"), note(2, "[TASK:RUNNING]\nRunning shell"), note(3, "[TASK:BLOCKED]\nwaiting"))
        val convB = listOf(StepLine(10, "me", "b"), note(11, "[TASK:RUNNING]\nRunning read_screen"), note(12, "[TASK:RESULT]\nread_screen — ok"))
        assertEquals(StepTracker.TaskState.WAITING, StepTracker.taskState(convA))
        assertEquals(StepTracker.TaskState.WORKING, StepTracker.taskState(convB))
        assertEquals(listOf("shell"), StepTracker.currentTaskSteps(convA).map { it.tool })
        assertEquals(listOf("read_screen"), StepTracker.currentTaskSteps(convB).map { it.tool })
        assertTrue(StepTracker.currentTaskSteps(emptyList()).isEmpty())
    }

    @Test fun restoredSavedConversationShowsStepsWithoutInventedDurations() {
        val restored = listOf(
            StepLine(111L, "me", "open settings"),
            StepLine(222L, "note", "[TASK:RUNNING]\nRunning read_screen"),
            StepLine(1_900_000_000L, "note", "[TASK:RESULT]\nread_screen — ok")
        )
        val steps = StepTracker.currentTaskSteps(restored)
        assertEquals(StepStatus.SUCCESS, steps[0].status)
        assertNull(steps[0].durationLabel(0))
    }

    @Test fun planNoteIsBoundedCleanedAndRoundTrips() {
        val raw = listOf("  Open   Settings ", "Open Settings", "", "Find Wi-Fi", "x".repeat(200), "c", "d", "e", "f")
        val note = PlanNote.format(raw)!!
        assertTrue(note.startsWith("[TASK:PLANNED]\n1. Open Settings\n2. Find Wi-Fi"))
        val titles = PlanNote.parse(note)
        assertEquals(PlanNote.MAX_STEPS, titles.size)
        assertTrue(titles.all { it.length <= PlanNote.MAX_TITLE })
    }

    @Test fun aSingleStepIsNotAPlan() {
        assertNull(PlanNote.format(listOf("Open Settings")))
        assertNull(PlanNote.format(listOf("a", "a")))
        assertNull(PlanNote.format(emptyList()))
    }

    @Test fun currentPlanBelongsToTheCurrentTaskOnly() {
        val old = listOf(StepLine(1, "me", "old"), note(2, PlanNote.format(listOf("a", "b"))!!))
        assertEquals(listOf("a", "b"), PlanNote.current(old))
        assertTrue(PlanNote.current(old + StepLine(3, "me", "new task")).isEmpty())
        assertTrue(PlanNote.parse("[TASK:RUNNING]\nRunning x").isEmpty())
    }
}
