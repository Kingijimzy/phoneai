package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class SoulLiveTest {
    @Test fun githubPathAllowlistIsReadOnlyAndBounded() {
        listOf("/user", "/user/repos", "/user/repos?per_page=5", "/notifications", "/repos/octo/hello", "/repos/octo/hello/issues?state=open", "/repos/octo/hello/actions/runs", "/search/issues?q=repo:octo/hello+is:open")
            .forEach { assertTrue(it, GithubConnector.pathAllowed(it)) }
        listOf("/user/keys", "/repos/octo/hello/contents/secret", "/repos/../admin", "/orgs/x/members", "/user/repos?x=" + "a".repeat(200), "https://evil.example/user", "/graphql")
            .forEach { assertFalse(it, GithubConnector.pathAllowed(it)) }
    }

    @Test fun githubSummaryIsShortLines() {
        val s = GithubConnector.summarize("""[{"full_name":"octo/hello","language":"Kotlin","updated_at":"2026-10-01"},{"full_name":"octo/two","state":"open"}]""")
        assertTrue(s.lines().size == 2 && s.contains("octo/hello") && s.contains("language=Kotlin"))
        assertTrue(GithubConnector.summarize("""{"login":"kingi","public_repos":7}""").contains("kingi"))
        assertEquals("not json", GithubConnector.summarize("not json"))
    }

    @Test fun imapParsingFindsIdsAndDecodesHeaders() {
        assertEquals(listOf(3, 9, 12), ImapParse.searchIds("* SEARCH 3 9 12\no3 OK done"))
        assertTrue(ImapParse.searchIds("* SEARCH\no3 OK").isEmpty())
        val resp = "* 9 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {80}\nFrom: Bank <a@bank.com>\nSubject: =?UTF-8?B?SGVsbG8gV29ybGQ=?=\nDate: Mon, 26 Oct 2026 10:00:00 +0100\n\n)\n" +
            "* 12 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {60}\nFrom: Tunde\nSubject: long\n subject here\nDate: Tue, 27 Oct 2026 08:00:00 +0100\n\n)\no5 OK"
        val hs = ImapParse.headers(resp)
        assertEquals(2, hs.size)
        assertEquals("Hello World", hs[0].subject); assertEquals("Bank <a@bank.com>", hs[0].from)
        assertEquals("long subject here", hs[1].subject)
    }

    @Test fun imapQuotingCannotInjectCommands() {
        assertEquals("\"a\\\"b\"", ImapParse.quote("a\"b"))
        assertFalse(ImapParse.quote("x\r\nA2 DELETE INBOX").contains("\n"))
    }

    @Test fun serviceCatalogMatchesPlannerScopes() {
        assertEquals("repo", ServiceCatalog.find("GitHub")!!.scope)
        assertEquals("https://www.googleapis.com/auth/gmail.readonly", ServiceCatalog.find("connect my gmail")!!.scope)
        assertNull(ServiceCatalog.find("tiktok"))
    }

    @Test fun capabilityGapsMergeResolveAndStayBounded() {
        var l = CapabilityBoardPolicy.record(emptyList(), "read my Gmail messages", "gmail", 1)
        l = CapabilityBoardPolicy.record(l, "read my gmail messages please", "gmail", 2)
        assertEquals(1, l.size); assertEquals(2, l[0].count)
        assertEquals(1, CapabilityBoardPolicy.open(l).size)
        assertTrue(CapabilityBoardPolicy.open(CapabilityBoardPolicy.markResolved(l, "gmail")).isEmpty())
        var big = emptyList<CapabilityGap>()
        for (i in 0 until 80) big = CapabilityBoardPolicy.record(big, "unique goal number $i about thing$i", "p$i", i.toLong())
        assertTrue(big.size <= CapabilityBoardPolicy.MAX)
    }

    @Test fun soulOnlyAlertsWhenMailGrows() {
        assertTrue(SoulPolicy.mailAlert(0, 3)); assertTrue(SoulPolicy.mailAlert(2, 5))
        assertFalse(SoulPolicy.mailAlert(3, 3)); assertFalse(SoulPolicy.mailAlert(5, 2)); assertFalse(SoulPolicy.mailAlert(0, 0))
        assertTrue(SoulPolicy.due(0, SoulPolicy.TICK_MS)); assertFalse(SoulPolicy.due(1000, 1000 + SoulPolicy.TICK_MS - 1))
    }

    @Test fun protocolNeverInvitesSecretsInChat() {
        val text = SoulProtocol.serviceLines(listOf("GitHub" to true, "Gmail (read-only)" to false), false, listOf("book a flight")).joinToString("\n")
        assertTrue(text.contains("GitHub = connected") && text.contains("Gmail (read-only) = not connected") && text.contains("pack: OFF"))
        assertTrue(text.contains("never ask for, accept, repeat or store passwords") && text.contains("connect_service") && text.contains("book a flight"))
    }

    @Test fun newToolsAreRegisteredAndPacked() {
        listOf("service_status", "connect_service", "github_account_read", "gmail_list_messages", "capability_note").forEach {
            assertNotNull(it, ToolRegistry.find(it)); assertNotNull(it, AgentPolicy.packForTool(it))
        }
        assertEquals(ToolAccess.READ, ToolRegistry.access("gmail_list_messages"))
        assertTrue(OrionIntegrationCapabilityPolicy.capabilities(setOf("github_account_read", "gmail_list_messages"), false).containsAll(setOf("github.account.read", "gmail.messages.read")))
    }
}
