package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWorkforceTest {
    @Test fun splitsObjectivesOnePerLineAndCapsThem() {
        val text = "- build the login screen\n2) write the api client\n\nshort\n* research competitors fully\n- extra fourth objective here"
        val parts = OrionWorkers.splitObjectives(text)
        assertEquals(listOf("build the login screen", "write the api client", "research competitors fully"), parts)
    }

    @Test fun workerToolsAdvertiseFanOutAndFanIn() {
        val spawn = ToolRegistry.find("spawn_worker")!!
        assertTrue(spawn.promptLine.contains("objectives?"))
        assertTrue(ToolRegistry.find("worker_result")!!.promptLine.contains("wait_seconds"))
        assertNotNull(ToolRegistry.find("brain"))
        assertTrue(ToolRegistry.find("brain")!!.promptLine.contains("platform"))
    }
}
