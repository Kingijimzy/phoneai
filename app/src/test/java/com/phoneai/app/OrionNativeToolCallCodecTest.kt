package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionNativeToolCallCodecTest {
    private val known: (String) -> Boolean = { it == "python_exec" || it == "device_info" }

    @Test fun parsesOpenAiCompatibleFunctionCall() {
        val body = """{"choices":[{"message":{"content":"I will inspect this.","tool_calls":[{"id":"call-1","type":"function","function":{"name":"python_exec","arguments":"{\"code\":\"print(2+2)\"}"}}]}}]}"""
        val turn = OrionNativeToolCallCodec.parse("openai", body, known)
        assertEquals("I will inspect this.", turn.text)
        assertEquals(1, turn.calls.size)
        assertEquals("python_exec", turn.calls[0].name)
        assertEquals("print(2+2)", turn.calls[0].arguments.getString("code"))
    }

    @Test fun parsesAnthropicToolUseBlock() {
        val body = """{"stop_reason":"tool_use","content":[{"type":"text","text":"Checking."},{"type":"tool_use","id":"toolu_1","name":"device_info","input":{"detail":"hardware"}}]}"""
        val turn = OrionNativeToolCallCodec.parse("anthropic", body, known)
        assertEquals("Checking.", turn.text)
        assertEquals("device_info", turn.calls.single().name)
        assertEquals("hardware", turn.calls.single().arguments.getString("detail"))
    }

    @Test fun parsesGeminiNativeFunctionCallParts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"Checking now."},{"functionCall":{"id":"gemini-call-1","name":"device_info","args":{"detail":"hardware"}}}]}}]}"""
        val turn = OrionNativeToolCallCodec.parse("gemini", body, known)
        assertEquals("Checking now.", turn.text)
        assertEquals("device_info", turn.calls.single().name)
        assertEquals("gemini-call-1", turn.calls.single().id)
        assertTrue(turn.calls.single().available)
        assertEquals("hardware", turn.calls.single().arguments.getString("detail"))
    }

    @Test fun parsesResponsesApiFunctionCall() {
        val body = """{"output":[{"type":"function_call","call_id":"fc_1","name":"python_exec","arguments":"{\"code\":\"print(7)\"}"},{"type":"message","content":[{"type":"output_text","text":"Done planning."}]}]}"""
        val turn = OrionNativeToolCallCodec.parse("opencode-zen", body, known)
        assertEquals("Done planning.", turn.text)
        assertEquals("fc_1", turn.calls.single().id)
        assertEquals("print(7)", turn.calls.single().arguments.getString("code"))
    }

    @Test fun ignoresUnknownMalformedAndOversizedCalls() {
        val body = """{"choices":[{"message":{"tool_calls":[{"id":"x","function":{"name":"shell_root","arguments":"{}"}},{"id":"y","function":{"name":"python_exec","arguments":"not-json"}}]}}]}"""
        val calls = OrionNativeToolCallCodec.parse("openai", body, known).calls
        assertEquals(2, calls.size)
        assertFalse(calls.first().available)
        assertEquals("shell_root", calls.first().name)
        assertNotNull(calls.last().validationError)
    }

    @Test fun preservesMultipleCallsInProviderOrder() {
        val body = """{"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"device_info","arguments":"{}"}},{"id":"b","function":{"name":"python_exec","arguments":"{\"code\":\"print(3)\"}"}}]}}]}"""
        val calls = OrionNativeToolCallCodec.parse("openai", body, known).calls
        assertEquals(2, calls.size)
        assertEquals(listOf("device_info", "python_exec"), calls.map { it.name })
    }

    @Test fun malformedArgumentsBecomeAFeedbackCallInsteadOfBeingExecuted() {
        val body = """{"choices":[{"message":{"tool_calls":[{"id":"bad","function":{"name":"python_exec","arguments":"not-json"}}]}}]}"""
        val call = OrionNativeToolCallCodec.parse("openai", body, known).calls.single()
        assertNotNull(call.validationError)
        assertTrue(call.arguments.length() == 0)
    }

    @Test fun nonJsonResponseIsAnEmptyTurn() {
        val turn = OrionNativeToolCallCodec.parse("openai", "plain text", known)
        assertEquals("", turn.text)
        assertTrue(turn.calls.isEmpty())
    }
}
