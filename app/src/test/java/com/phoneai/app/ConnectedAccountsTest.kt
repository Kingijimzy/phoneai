package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ConnectedAccountsTest {
    @Test fun parsesImapSearchAndHeaders() {
        assertEquals(listOf(4, 9, 12), ConnectorParsing.parseSearch("* SEARCH 4 9 12\r\nA003 OK done"))
        assertTrue(ConnectorParsing.parseSearch("* SEARCH\r\nA003 OK").isEmpty())
        val raw = "* 9 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {80}\r\nFrom: Tunde <t@x.com>\r\nSubject: Rent\r\n due friday\r\nDate: Mon, 26 Oct 2026 10:00:00 +0100\r\n\r\n)\r\n" +
            "* 12 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {60}\r\nFrom: =?UTF-8?B?QW1h?= <a@x.com>\r\nSubject: =?UTF-8?Q?Hi_there?=\r\n\r\n)\r\n"
        val h = ConnectorParsing.parseHeaders(raw)
        assertEquals(2, h.size)
        assertEquals("Rent due friday", h[0].subject.replace("  ", " "))
        assertEquals("Ama <a@x.com>", h[1].from)
        assertEquals("Hi there", h[1].subject)
    }

    @Test fun formatsMailWithoutBodiesAndMarksItUntrusted() {
        assertEquals("No unread messages in the inbox.", ConnectorParsing.formatMail(emptyList(), 0))
        val t = ConnectorParsing.formatMail(listOf(MailHeader("A", "Hello", "")), 3)
        assertTrue(t.contains("Unread: 3") && t.contains("untrusted") && t.contains("- A | Hello"))
    }

    @Test fun formatsGithubJson() {
        val n = ConnectorParsing.formatGithubNotifications("""[{"reason":"mention","subject":{"title":"Fix bug"},"repository":{"full_name":"me/app"}}]""")
        assertTrue(n.contains("me/app: Fix bug (mention)"))
        assertEquals("No unread GitHub notifications.", ConnectorParsing.formatGithubNotifications("[]"))
        assertTrue(ConnectorParsing.formatGithubNotifications("nope").startsWith("error"))
        assertTrue(ConnectorParsing.formatGithubRepos("""[{"full_name":"me/app","private":true,"open_issues_count":2,"pushed_at":"2026-10-25T10:00:00Z"}]""").contains("me/app (private) · 2 open issues"))
    }

    @Test fun imapQuotingRefusesInjection() {
        assertEquals("\"a\\\"b\"", ConnectorParsing.imapQuote("a\"b"))
        assertNull(ConnectorParsing.imapQuote("x\r\nA002 DELETE INBOX"))
        assertNull(ConnectorParsing.imapQuote(""))
    }

    @Test fun standingOrderRules() {
        assertNull(StandingOrderPolicy.validate("gmail_unread", 30))
        assertNotNull(StandingOrderPolicy.validate("gmail_unread", 5))
        assertNotNull(StandingOrderPolicy.validate("rm_everything", 60))
        val o = StandingOrder("brief", 60, 0L, "")
        assertTrue(StandingOrderPolicy.due(o, 61 * 60_000L))
        assertFalse(StandingOrderPolicy.due(o.copy(lastRunAt = 10 * 60_000L), 30 * 60_000L))
    }

    @Test fun notifiesOnlyOnNewRealResults() {
        val r = "Unread: 2. Latest..."
        assertTrue(StandingOrderPolicy.shouldNotify("", r))
        assertFalse(StandingOrderPolicy.shouldNotify(StandingOrderPolicy.digest(r), r))
        assertFalse(StandingOrderPolicy.shouldNotify("", "error: not connected"))
        assertFalse(StandingOrderPolicy.shouldNotify("", "No unread messages in the inbox."))
    }

    @Test fun connectorToolsMakeThePlannerReportRealCapabilities() {
        val caps = OrionIntegrationCapabilityPolicy.capabilities(setOf("github_account_read", "gmail_messages_read"), false)
        assertTrue("github.account.read" in caps && "gmail.messages.read" in caps)
        listOf("github_account_read", "gmail_messages_read").forEach { assertEquals(ToolAccess.READ, ToolRegistry.access(it)) }
        listOf("open_connections", "standing_order").forEach { assertEquals(ToolAccess.WRITE, ToolRegistry.access(it)) }
        assertEquals("connections", AgentPolicy.packForTool("gmail_messages_read"))
        assertTrue(AgentPolicy.packs.any { it.id == "connections" && it.implemented })
    }
}
