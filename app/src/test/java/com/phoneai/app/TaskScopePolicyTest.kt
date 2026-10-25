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

    @Test fun shortFollowUpInheritsRecentActionScope() {
        val prev = "open WhatsApp and send kingi jimzy good morning"
        val scope = TaskScopePolicy.scopeFor("yes go ahead", prev, 30_000)
        assertNull(TaskScopePolicy.blockReason(scope, "open_app"))
        assertNotNull(TaskScopePolicy.blockReason("yes go ahead", "open_app"))
    }

    @Test fun staleOrLongFollowUpDoesNotInherit() {
        val prev = "open WhatsApp and send kingi jimzy good morning"
        assertEquals("yes go ahead", TaskScopePolicy.scopeFor("yes go ahead", prev, 11 * 60 * 1000L))
        val long = "tell me a long story about the history of the roman empire please now"
        assertEquals(long, TaskScopePolicy.scopeFor(long, prev, 1000))
    }

    @Test fun openWhatsAppRequestIsPhoneAutomation() {
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("open WhatsApp and send kingi jimzy this message good morning chams"))
    }
}
