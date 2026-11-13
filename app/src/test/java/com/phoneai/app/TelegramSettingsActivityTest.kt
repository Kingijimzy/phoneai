package com.phoneai.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TelegramSettingsActivityTest {
    private fun findText(view: View, expected: String): View? {
        if (view is TextView && view.text?.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    @Test fun capabilityBoardExposesTelegramAndOpensItsRealSettingsScreen() {
        val board = Robolectric.buildActivity(CapabilityBoardActivity::class.java).setup().get()
        val root = board.window.decorView
        assertNotNull(findText(root, "Telegram Bot"))
        val configure = findText(root, "Configure / verify")
        assertNotNull(configure)
        configure!!.performClick()
        val launched = shadowOf(board).nextStartedActivity
        assertEquals(TelegramSettingsActivity::class.java.name, launched.component?.className)
        board.finish()
    }

    @Test fun telegramSettingsScreenExposesSaveVerifyTestAndListenerActions() {
        val activity = Robolectric.buildActivity(TelegramSettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        assertNotNull(findText(root, "SAVE AND VERIFY BOT"))
        assertNotNull(findText(root, "CHECK CONNECTION"))
        assertNotNull(findText(root, "SEND TEST MESSAGE"))
        assertNotNull(findText(root, "START BACKGROUND LISTENER"))
        assertNotNull(findText(root, "DISCONNECT TELEGRAM"))
        activity.finish()
    }
}
