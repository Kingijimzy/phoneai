package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWorkspaceAppsTest {
    private fun ok(p: OrionWorkspaceApps.Plan) = (p as OrionWorkspaceApps.Plan.Ok).req
    private fun err(p: OrionWorkspaceApps.Plan) = (p as OrionWorkspaceApps.Plan.Error).message

    @Test fun driveSearchEscapesQuotesAndSortsByRecent() {
        val r = ok(OrionWorkspaceApps.googlePlan("drive_search", JSONObject().put("query", "Kingi's plan")))
        assertEquals("GET", r.method); assertTrue(r.url.contains("orderBy=modifiedTime")); assertTrue(r.url.contains("Kingi%5C%27s") || r.url.contains("Kingi%5C%27s".lowercase()) || r.url.contains("%5C%27"))
    }

    @Test fun docsAndSheetsRequestsAreWellFormed() {
        val create = ok(OrionWorkspaceApps.googlePlan("docs_create", JSONObject().put("title", "Notes").put("text", "hi")))
        assertTrue(create.url.endsWith("/v1/documents")); assertEquals("docs_fill:hi", create.note)
        val append = ok(OrionWorkspaceApps.googlePlan("sheets_append", JSONObject().put("id", "S1").put("rows", JSONArray("[[\"a\",1]]"))))
        assertTrue(append.url.contains(":append")); assertTrue(append.body!!.contains("\"values\""))
        assertTrue(err(OrionWorkspaceApps.googlePlan("sheets_append", JSONObject().put("id", "S1"))).contains("rows"))
        assertTrue(err(OrionWorkspaceApps.googlePlan("nope", JSONObject())).contains("unknown google action"))
    }

    @Test fun notionChunksLongTextAndNeedsAParent() {
        val big = "x".repeat(4000)
        assertEquals(3, OrionWorkspaceApps.paragraphs(big).length())
        assertTrue(err(OrionWorkspaceApps.notionPlan("create", JSONObject().put("title", "t"))).contains("parent_id"))
        val r = ok(OrionWorkspaceApps.notionPlan("append", JSONObject().put("id", "P").put("text", "line")))
        assertEquals("PATCH", r.method)
    }

    @Test fun errorsNameTheRealCause() {
        assertTrue(OrionWorkspaceApps.explain("google", 403, "Request had insufficient authentication scopes").contains("scope"))
        assertTrue(OrionWorkspaceApps.explain("notion", 404, "").contains("shared with the integration"))
        assertTrue(OrionWorkspaceApps.explain("google", 401, "").contains("reconnect"))
    }

    @Test fun summaryListsFilesAndSheetRows() {
        assertTrue(OrionWorkspaceApps.summarize("drive_search", """{"files":[{"id":"1","name":"Plan","mimeType":"application/vnd.google-apps.document"}]}""").contains("Plan"))
        assertTrue(OrionWorkspaceApps.summarize("sheets_read", """{"values":[["a","b"],["1","2"]]}""").contains("a | b"))
    }

    @Test fun toolsRouteAndMapToNodes() {
        assertEquals("docs", OrionNodes.nodeFor("google", JSONObject().put("action", "docs_create")))
        assertEquals("sheets", OrionNodes.nodeFor("google", JSONObject().put("action", "sheets_read")))
        assertEquals("drive", OrionNodes.nodeFor("google", JSONObject().put("action", "drive_search")))
        assertEquals("notion", OrionNodes.nodeFor("notion"))
        assertEquals(emptyList<String>(), OrionSelfTest.unrouted())
        assertTrue("drive" in OrionNodes.linked(emptyList(), setOf("drive")))
    }
}
