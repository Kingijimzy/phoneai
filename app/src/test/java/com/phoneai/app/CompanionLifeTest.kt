package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import com.phoneai.app.CompanionLife.Reaction
import com.phoneai.app.CompanionMood.Mood

class CompanionLifeTest {
    @Test fun winCelebratesAndLossFlinches() {
        assertEquals(Reaction.CELEBRATE, CompanionLife.reactionTo(10.0, 25.0, 1000.0, Mood.CALM, Mood.HAPPY))
        assertEquals(Reaction.FLINCH, CompanionLife.reactionTo(10.0, -5.0, 1000.0, Mood.CALM, Mood.CALM))
        assertEquals(Reaction.NONE, CompanionLife.reactionTo(10.0, 10.2, 1000.0, Mood.CALM, Mood.CALM))
        assertEquals(Reaction.NONE, CompanionLife.reactionTo(null, 50.0, 1000.0, null, Mood.CALM))
    }
    @Test fun newAlertAlarmsOnlyOnChange() {
        assertEquals(Reaction.ALARM, CompanionLife.reactionTo(0.0, 0.0, 1000.0, Mood.CALM, Mood.ALERT))
        assertEquals(Reaction.NONE, CompanionLife.reactionTo(0.0, 0.0, 1000.0, Mood.ALERT, Mood.ALERT))
        assertEquals(Reaction.NONE, CompanionLife.reactionTo(0.0, 0.0, 1000.0, null, Mood.ALERT))
    }
    @Test fun greetingMentionsTimeAbsenceAndNews() {
        val g = CompanionLife.greeting(9, 600, 2, Mood.CALM, 45)
        assertTrue(g, g.startsWith("Good morning.") && g.contains("Welcome back") && g.contains("2 open positions") && g.contains("45m"))
        assertTrue(CompanionLife.greeting(2, 0, 1, Mood.WORRIED, null).startsWith("Still up."))
        assertFalse(CompanionLife.greeting(14, 5, 0, Mood.HAPPY, 500).contains("news"))
    }
    @Test fun chipGroupsAreSane() {
        val labels = ChatGroups.coach.flatMap { it.chips.map { c -> c.label } }
        assertEquals(labels.size, labels.toSet().size)
        ChatGroups.coach.forEach { g ->
            assertTrue(g.name, g.chips.isNotEmpty() && g.chips.size <= 8)
            g.chips.forEach { assertTrue(it.label, (it.prompt != null) != (it.action != null)) }
        }
        assertEquals(6, ChatGroups.coach.size)
        assertEquals("Start", ChatGroups.coach.first().name)
    }
}
