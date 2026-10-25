package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class TaskScopePolicyTest {
    @Test fun classifiesDistinctTaskModes() {
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain how Bluetooth works"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Open WhatsApp and read my last chat"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Research recent Android accessibility changes online"))
        assertEquals(TaskMode.READ_ONLY, TaskScopePolicy.modeFor("Read-only: inspect my notifications without changing anything"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("What time is it"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("What is the weather forecast in Lagos"))
    }

    @Test fun explicitWhatsAppSendRequestAllowsPhoneToolScope() {
        val request = "open WhatsApp and send kingi jimzy this message good morning chams"
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor(request))
        assertNull(TaskScopePolicy.blockReason(request, "open_app"))
        assertNull(TaskScopePolicy.blockReason(request, "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason(request, "bybit_public_ticker"))
    }

    @Test fun explicitActionIsNotDowngradedByConversationWording() {
        assertEquals(
            TaskMode.PHONE_AUTOMATION,
            TaskScopePolicy.modeFor("Please help me with this: open WhatsApp and send good morning")
        )
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain what WhatsApp is used for"))
    }

    @Test fun chatCannotInvokeToolsEvenIfModelRequestsOne() {
        assertNotNull(TaskScopePolicy.blockReason("Explain how Bluetooth works", "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason("Explain how Bluetooth works", "open_app"))
    }

    @Test fun researchAndReadOnlyCannotMutateState() {
        assertNull(TaskScopePolicy.blockReason("Research Android accessibility online", "web_search"))
        assertNotNull(TaskScopePolicy.blockReason("Research Android accessibility online", "open_app"))
        assertNull(TaskScopePolicy.blockReason("Read-only inspect my notifications", "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason("Read-only inspect my notifications", "type"))
    }

    @Test fun unrelatedSpecialistGroupsAreBlocked() {
        assertNotNull(TaskScopePolicy.blockReason("Open WhatsApp and read my last chat", "bybit_public_ticker"))
        assertNotNull(TaskScopePolicy.blockReason("What is the BTC price on Bybit", "telegram_send"))
        assertNull(TaskScopePolicy.blockReason("What is the BTC price on Bybit", "bybit_public_ticker"))
        assertNull(TaskScopePolicy.blockReason("Open Telegram and read my messages", "telegram_status"))
    }

    @Test fun unknownToolsAreAlwaysDenied() {
        assertNotNull(TaskScopePolicy.blockReason("Open WhatsApp", "run_shell"))
    }
}
