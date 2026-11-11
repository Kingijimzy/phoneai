package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionResponseCompletenessTest {
    @Test fun detectsOpenAiTokenLimit() {
        assertTrue(OrionResponseCompleteness.wasTruncated("openai",
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""))
    }

    @Test fun doesNotTreatNormalToolCallAsTruncated() {
        assertFalse(OrionResponseCompleteness.wasTruncated("openai",
            """{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[]}}]}"""))
    }

    @Test fun detectsAnthropicMaxTokens() {
        assertTrue(OrionResponseCompleteness.wasTruncated("anthropic",
            """{"stop_reason":"max_tokens","content":[{"type":"text","text":"partial"}]}""", true))
    }

    @Test fun detectsGeminiMaxTokens() {
        assertTrue(OrionResponseCompleteness.wasTruncated("gemini",
            """{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""))
    }

    @Test fun detectsResponsesApiOutputLimit() {
        assertTrue(OrionResponseCompleteness.wasTruncated("opencode-zen",
            """{"incomplete_details":{"reason":"max_output_tokens"}}"""))
    }

    @Test fun acceptsCompleteProviderResponses() {
        assertFalse(OrionResponseCompleteness.wasTruncated("gemini",
            """{"candidates":[{"finishReason":"STOP"}]}"""))
        assertFalse(OrionResponseCompleteness.wasTruncated("anthropic",
            """{"stop_reason":"end_turn"}""", true))
    }
}
