package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionToolContractCodecTest {
    @Test fun emitsOpenAiFunctionEnvelopeWithRegisteredSchema() {
        val tool = ToolDefinition("sample", "sample {name, count?: integer, enabled?: boolean}: sample contract", "sample description", ToolAccess.READ)
        val json = OrionToolContractCodec.openAi(listOf(tool)).getJSONObject(0)
        assertEquals("function", json.getString("type"))
        val function = json.getJSONObject("function")
        assertEquals("sample", function.getString("name"))
        val schema = function.getJSONObject("parameters")
        assertEquals("object", schema.getString("type"))
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("name").getString("type"))
        assertEquals("integer", schema.getJSONObject("properties").getJSONObject("count").getString("type"))
        assertEquals("boolean", schema.getJSONObject("properties").getJSONObject("enabled").getString("type"))
        assertEquals("name", schema.getJSONArray("required").getString(0))
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test fun emitsAnthropicInputSchemaAndGeminiFunctionDeclarations() {
        val tool = ToolDefinition("inspect", "inspect {path}: inspect a file", "inspect a file", ToolAccess.READ)
        val anthropic = OrionToolContractCodec.anthropic(listOf(tool)).getJSONObject(0)
        assertEquals("inspect", anthropic.getString("name"))
        assertEquals("object", anthropic.getJSONObject("input_schema").getString("type"))

        val gemini = OrionToolContractCodec.gemini(listOf(tool)).getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("inspect", gemini.getString("name"))
        assertTrue(gemini.getJSONObject("parameters").getJSONObject("properties").has("path"))
        val responseTool = OrionToolContractCodec.responses(listOf(tool)).getJSONObject(0)
        assertFalse(responseTool.getBoolean("strict"))
    }

    @Test fun modelsDynamicMcpArgumentsAsAnOpenJsonObject() {
        val mcp = ToolRegistry.find("mcp_call") ?: error("mcp_call must be registered")
        val arguments = OrionToolContractCodec.parameters(mcp)
            .getJSONObject("properties").getJSONObject("arguments")
        assertEquals("object", arguments.getString("type"))
        assertTrue(arguments.getBoolean("additionalProperties"))
        val extension = ToolRegistry.find("extension_run") ?: error("extension_run must be registered")
        val extensionInput = OrionToolContractCodec.parameters(extension)
            .getJSONObject("properties").getJSONObject("input")
        assertEquals("object", extensionInput.getString("type"))
        assertTrue(extensionInput.getBoolean("additionalProperties"))

        // MCP arguments are supplied by each remote tool's own schema, not by Orion's
        // generic mcp_call wrapper. They must pass through without rejecting valid fields.
        val call = org.json.JSONObject()
            .put("name", "mcp_list_tools")
            .put("arguments", org.json.JSONObject()
                .put("server_id", "roamies")
                .put("limit", 10)
                .put("filters", org.json.JSONObject().put("enabled", true)))
        assertNull(OrionToolContractCodec.validateArguments(mcp, call))
    }

    @Test fun infersArrayAndDefaultedLiteralContracts() {
        val ask = ToolRegistry.find("ask_user") ?: error("ask_user must be registered")
        val options = OrionToolContractCodec.parameters(ask)
            .getJSONObject("properties").getJSONObject("options")
        assertEquals("array", options.getString("type"))
        assertEquals("string", options.getJSONObject("items").getString("type"))
        val extension = ToolRegistry.find("extension_create") ?: error("extension_create must be registered")
        val schema = OrionToolContractCodec.parameters(extension)
        assertFalse(schema.getJSONArray("required").toString().contains("runtime"))
        val runtimeOptions = schema.getJSONObject("properties")
            .getJSONObject("runtime").getJSONArray("enum")
        assertEquals("python_full", runtimeOptions.getString(0))
        assertEquals("python", runtimeOptions.getString(1))
    }

    @Test fun derivesNestedArrayAndObjectArgumentSchemas() {
        val tool = ToolDefinition(
            "batch",
            "batch {steps:[{stepIndex: integer, args:{code}}]}: execute a batch",
            "execute a batch",
            ToolAccess.WRITE
        )
        val steps = OrionToolContractCodec.parameters(tool)
            .getJSONObject("properties").getJSONObject("steps")
        assertEquals("array", steps.getString("type"))
        val item = steps.getJSONObject("items")
        assertEquals("object", item.getString("type"))
        assertEquals("integer", item.getJSONObject("properties").getJSONObject("stepIndex").getString("type"))
        assertEquals("object", item.getJSONObject("properties").getJSONObject("args").getString("type"))
    }

    @Test fun emptySignatureProducesStrictEmptyObjectSchema() {
        val tool = ToolDefinition("status", "status {}: report status", "report status", ToolAccess.READ)
        val schema = OrionToolContractCodec.parameters(tool)
        assertEquals(0, schema.getJSONObject("properties").length())
        assertEquals(0, schema.getJSONArray("required").length())
    }

    @Test fun selectsRelevantNativeToolsWithinProviderLimit() {
        val selected = ToolRegistry.nativeToolsFor("inspect MCP servers and list remote tools")
        assertTrue(selected.size <= 96)
        assertTrue(selected.any { it.name == "mcp_status" })
        assertTrue(selected.any { it.name == "mcp_list_tools" })
        assertTrue(selected.any { it.name == "mcp_call" })
        assertTrue(selected.any { it.name == "open_app" })
        assertTrue(selected.any { it.name == "read_screen" })
        assertTrue(selected.any { it.name == "python_exec" })
        assertTrue(selected.any { it.name == "workspace_write" })
        assertTrue(selected.any { it.name == "mcp_list_resources" })
        assertTrue(selected.any { it.name == "mcp_get_prompt" })
        assertTrue(selected.all { ToolRegistry.find(it.name) != null })
        val phone = ToolRegistry.nativeToolsFor("open WhatsApp and read the screen")
        assertTrue(phone.any { it.name == "lookup_app" })
        assertTrue(phone.any { it.name == "open_app" })
        val resumed = ToolRegistry.nativeToolsFor("continue", setOf("browser_click_verify"))
        assertTrue(resumed.any { it.name == "browser_click_verify" })
    }


    @Test fun everyRegisteredAccessibilityOperationIsCallableInTheDefaultModelTurn() {
        val exposed = ToolRegistry.nativeToolsFor("hello").map { it.name }.toSet()
        val missing = ToolRegistry.names()
            .filter { ToolRegistry.requiresAccessibility(it) && it !in exposed }
        assertTrue("Accessibility capabilities hidden from the model: $missing", missing.isEmpty())
        assertTrue(exposed.containsAll(setOf(
            "read_screen", "click", "click_text", "tap", "long_press", "swipe",
            "swipe_path", "scroll", "type", "key", "look", "play",
            "routine_run", "routine_resume", "open_app"
        )))
    }

    @Test fun parsesActualRegistryContractsWithoutLosingNames() {
        val registered = ToolRegistry.all
        val openAiNames = OrionToolContractCodec.openAi(registered)
        assertEquals(registered.size, openAiNames.length())
        val names = (0 until openAiNames.length()).map {
            openAiNames.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertEquals(ToolRegistry.names(), names.toSet())
    }
    @Test fun exposesMcpDiscoveryForOrdinaryLanguageRequests() {
        val selected = ToolRegistry.nativeToolsFor("Find the latest information about my business records")
        assertTrue(selected.any { it.name == "mcp_status" })
        assertTrue(selected.any { it.name == "mcp_list_tools" })
        assertTrue(selected.any { it.name == "mcp_call" })
    }

}
