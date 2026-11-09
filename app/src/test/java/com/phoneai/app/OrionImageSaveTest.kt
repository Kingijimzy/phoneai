package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionImageSaveTest {
    @Test fun factToolsAreRegistered() {
        listOf("fact_add", "fact_query", "fact_forget").forEach { assertNotNull(it, ToolRegistry.find(it)) }
    }
}
