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
        assertEquals("path", gemini.getJSONObject("parameters").getJSONObject("properties").getString("path"))
    }

    @Test fun emptySignatureProducesStrictEmptyObjectSchema() {
        val tool = ToolDefinition("status", "status {}: report status", "report status", ToolAccess.READ)
        val schema = OrionToolContractCodec.parameters(tool)
        assertEquals(0, schema.getJSONObject("properties").length())
        assertEquals(0, schema.getJSONArray("required").length())
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
}
