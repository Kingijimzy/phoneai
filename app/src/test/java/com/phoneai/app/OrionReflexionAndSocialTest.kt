package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionReflexionAndSocialTest {
    @Test fun failureDetection() {
        assertTrue(OrionReflexion.looksFailed("error: command not found: ffmpeg"))
        assertTrue(OrionReflexion.looksFailed("exit=127\nsh: foo: not found"))
        assertFalse(OrionReflexion.looksFailed("EXIT 0 · SUCCESS\nfile not found handled"))
        assertFalse(OrionReflexion.looksFailed("Here are 5 results"))
    }
    @Test fun reflectOnlyWhenAllFailedAndBounded() {
        assertTrue(OrionReflexion.roundFailed(listOf("error: a", "failed b")))
        assertFalse(OrionReflexion.roundFailed(listOf("error: a", "ok done")))
        assertFalse(OrionReflexion.roundFailed(emptyList()))
        assertTrue(OrionReflexion.shouldReflect(true, 0, true))
        assertFalse(OrionReflexion.shouldReflect(true, OrionReflexion.MAX_REFLECTIONS_PER_COMMAND, true))
        assertFalse(OrionReflexion.shouldReflect(true, 0, false))
    }
    @Test fun socialCatalogAndAliases() {
        assertEquals("x", OrionSocialConnector.find("Twitter")?.id)
        assertEquals("facebook", OrionSocialConnector.find("fb")?.id)
        assertNull(OrionSocialConnector.find("myspace"))
    }
    @Test fun socialPlanning() {
        val x = OrionSocialConnector.find("x")!!
        val post = OrionSocialConnector.plan(x, "post", JSONObject().put("text", "hello"), "")!!
        assertEquals("POST", post.method); assertTrue(post.json)
        assertEquals("https://api.x.com/2/tweets", post.url)
        val fb = OrionSocialConnector.find("facebook")!!
        assertEquals("message=hi+there", OrionSocialConnector.plan(fb, "post", JSONObject().put("text", "hi there"), "")!!.body)
        assertNull(OrionSocialConnector.plan(fb, "request", JSONObject().put("path", "//evil.com/x"), ""))
        assertNull(OrionSocialConnector.plan(fb, "request", JSONObject().put("path", "/a/../b"), ""))
        val m = OrionSocialConnector.find("mastodon")!!
        assertEquals("https://mastodon.social/api/v1/statuses", OrionSocialConnector.plan(m, "post", JSONObject().put("text", "x"), "mastodon.social")!!.url)
        assertNull(OrionSocialConnector.plan(x, "post", JSONObject(), ""))
    }

    @Test fun errorsExplainRealCause() {
        val fb = OrionSocialConnector.find("facebook")!!
        val e = OrionSocialConnector.explain(fb, 403, "{\"error\":{\"message\":\"(#200) If posting to a group, requires app being installed\"}}")
        assertTrue(e.contains("Page"))
        assertTrue(OrionSocialConnector.explain(fb, 401, "Error validating access token").contains("token"))
        assertEquals("", OrionSocialConnector.explain(fb, 404, "nope"))
    }
    @Test fun pagesPlanOnlyForFacebook() {
        val fb = OrionSocialConnector.find("facebook")!!
        assertTrue(OrionSocialConnector.plan(fb, "pages", JSONObject(), "")!!.url.contains("/me/accounts"))
        assertNull(OrionSocialConnector.plan(OrionSocialConnector.find("x")!!, "pages", JSONObject(), ""))
    }
}
