package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionSessionContextProviderTest {
    @Test fun chatAndLiveVoiceUseTheSameCentralBrainAndSessionSnapshot() {
        val context: Context = RuntimeEnvironment.getApplication()
        val chat = OrionSessionContextProvider.forChat(context, "What did we decide about the current task?")
        val voice = OrionSessionContextProvider.forLiveVoice(context)

        assertTrue(chat.contains("ORION CENTRAL BRAIN — SHARED EXECUTIVE CONTRACT"))
        assertTrue(voice.contains("ORION CENTRAL BRAIN — SHARED EXECUTIVE CONTRACT"))
        assertTrue(chat.contains("CENTRAL BRAIN SESSION SNAPSHOT"))
        assertTrue(voice.contains("CENTRAL BRAIN SESSION SNAPSHOT"))
        assertTrue(chat.contains("Registered tool names"))
        assertTrue(voice.contains("Registered tool names"))
    }

    @Test fun bothSurfacesLoadCurrentSessionIdentityFromTheSameSnapshotBuilder() {
        val context: Context = RuntimeEnvironment.getApplication()
        val chat = OrionSessionContextProvider.forChat(context, "continue")
        val voice = OrionSessionContextProvider.forLiveVoice(context)
        val chatSession = Regex("session_id=([^;]+)").find(chat)?.groupValues?.get(1)
        val voiceSession = Regex("session_id=([^;]+)").find(voice)?.groupValues?.get(1)
        assertEquals(chatSession, voiceSession)
    }
}
