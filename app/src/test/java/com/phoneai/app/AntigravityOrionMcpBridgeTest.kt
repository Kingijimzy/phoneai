package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class AntigravityOrionMcpBridgeTest {
    private fun post(url: String, body: JSONObject, session: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return connection
    }

    @Test fun exposesOnlyCurrentToolsAndRoutesCallsToTheSuppliedExecutor() {
        val calls = AtomicInteger()
        val tool = ToolDefinition("battery", "battery {}: report battery status", "report battery status", ToolAccess.READ)
        AntigravityOrionMcpBridge(listOf(tool)) { name, args ->
            assertEquals("battery", name)
            assertEquals("full", args.getString("detail"))
            calls.incrementAndGet()
            "Battery 82 percent"
        }.use { bridge ->
            val initialize = post(
                bridge.serverUrl,
                JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", JSONObject().put("protocolVersion", "2025-03-26"))
            )
            assertEquals(200, initialize.responseCode)
            val session = initialize.getHeaderField("Mcp-Session-Id")
            assertTrue(!session.isNullOrBlank())
            initialize.inputStream.close()
            initialize.disconnect()

            val list = post(
                bridge.serverUrl,
                JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"),
                session
            )
            val listResult = JSONObject(list.inputStream.bufferedReader().use { it.readText() })
            assertEquals("battery", listResult.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
            assertEquals("object", listResult.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getJSONObject("inputSchema").getString("type"))
            assertEquals("string", listResult.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("detail").getString("type"))
            list.disconnect()

            val call = post(
                bridge.serverUrl,
                JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                    .put("params", JSONObject().put("name", "battery").put("arguments", JSONObject().put("detail", "full"))),
                session
            )
            val callResult = JSONObject(call.inputStream.bufferedReader().use { it.readText() })
            val content = callResult.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
            assertEquals("Battery 82 percent", content)
            assertEquals(1, calls.get())
            call.disconnect()
        }
    }

    @Test fun rejectsRequestsUsingAnotherSessionToken() {
        AntigravityOrionMcpBridge(
            listOf(ToolDefinition("battery", "battery {}: read status", "read status", ToolAccess.READ))
        ) { _, _ -> "ok" }.use { bridge ->
            val initialize = post(
                bridge.serverUrl,
                JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            )
            val session = initialize.getHeaderField("Mcp-Session-Id")
            initialize.inputStream.close()
            initialize.disconnect()
            val invalid = post(
                bridge.serverUrl,
                JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"),
                "not-the-session"
            )
            assertEquals(404, invalid.responseCode)
            invalid.disconnect()
            assertTrue(!session.isNullOrBlank())
        }
    }
}
