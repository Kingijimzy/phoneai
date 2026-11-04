package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import com.phoneai.app.DeskHealth.Level

class DeskHealthTest {
    private fun inputs(ai: Boolean = true, keys: Boolean = false, test: Boolean = true, ro: Boolean = true, deriv: Boolean = false, real: Boolean = false,
                       cal: Int = 0, pb: Int = 0, j: Int = 0, auto: Boolean = true, mic: Boolean = false, voice: Boolean = false, wake: Boolean = false) =
        DeskHealth.Inputs(ai, keys, test, ro, deriv, real, cal, pb, j, auto, mic, voice, wake)

    @Test fun freshInstallListsWhatToDo() {
        val items = DeskHealth.check(inputs(ai = false))
        assertEquals(Level.TODO, items.first { it.name == "AI provider" }.level)
        assertEquals(Level.TODO, items.first { it.name == "Playbook" }.level)
        assertEquals(Level.OK, items.first { it.name == "Order safety" }.level)
        assertTrue(DeskHealth.summary(items), DeskHealth.summary(items).contains("need"))
    }
    @Test fun realMoneyAndRealDerivAreFlagged() {
        val items = DeskHealth.check(inputs(keys = true, test = false, ro = false, deriv = true, real = true))
        assertEquals(Level.WARN, items.first { it.name == "Order safety" }.level)
        assertEquals(Level.WARN, items.first { it.name == "Deriv" }.level)
    }
    @Test fun textShowsMarks() {
        val t = DeskHealth.text(DeskHealth.check(inputs(pb = 2, cal = 5, j = 3, keys = true, mic = true, wake = true)))
        assertTrue(t, t.startsWith("Setup check:") && t.contains("✓ Playbook: 2 saved strategies.") && t.contains("✓ News calendar"))
    }
    @Test fun tourCoversTheMainControls() {
        listOf("Tap the character", "Mic", "Talk", "Wake", "risk gate").forEach { assertTrue(it, DeskTour.text.contains(it)) }
    }
}
