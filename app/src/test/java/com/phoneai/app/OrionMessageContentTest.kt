package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMessageContentTest {
    @Test fun versionedContentRoundTripsEverySemanticBlock() {
        val content = OrionMessageContent(blocks = listOf(
            OrionMessageBlock.Text("The result is ready."),
            OrionMessageBlock.Code("kotlin", "Main.kt", "fun main() = Unit"),
            OrionMessageBlock.Terminal("gradle test", "", "BUILD SUCCESSFUL", "", 0, "completed", 1200),
            OrionMessageBlock.GeneratedFile("artifact-1", "report.pdf", "application/pdf", 42, "abc", "available"),
            OrionMessageBlock.Approval("approval-1", "Install extension", "Adds an Android permission.", "high", "pending"),
            OrionMessageBlock.TaskReceipt("task-1", "Create a report", "completed", listOf("write", "verify"), "sha256:abc"),
            OrionMessageBlock.Thinking("verifying", "Checking output", 123L),
            OrionMessageBlock.ToolResult("Tool returned successfully"),
            OrionMessageBlock.Research("Two useful sources", listOf("https://example.com")),
            OrionMessageBlock.Image("[IMAGE]")
        ))
        assertEquals(content, OrionMessageContent.fromJson(content.toJson()))
    }

    @Test fun ordinaryReplyWithResearchWordsStaysPlainText() {
        val content = OrionMessageContent.fromLegacy("ai", "Here is my research summary of the idea.")
        assertEquals(1, content.blocks.size)
        assertTrue(content.blocks.single() is OrionMessageBlock.Text)
    }

    @Test fun fencedCodeBecomesAnExplicitCodeBlockWithoutChangingSource() {
        val source = """Intro

```python
print('ok')
```
Done"""
        val content = OrionMessageContent.fromLegacy("ai", source)
        assertTrue(content.blocks.any { it is OrionMessageBlock.Code && it.language == "python" })
        assertEquals("""Intro

""", (content.blocks[0] as OrionMessageBlock.Text).markdown)
        assertEquals("""print('ok')
""", (content.blocks[1] as OrionMessageBlock.Code).source)
        assertEquals("""
Done""", (content.blocks[2] as OrionMessageBlock.Text).markdown)
    }

    @Test fun legacyShellAndApprovalNotesBecomeTypedBlocks() {
        val shell = OrionMessageContent.fromLegacy("note", "[SHELL:FAILED]\\n$ python broken.py\\n\\u2063OUT\\nTraceback")
        val terminal = shell.blocks.single() as OrionMessageBlock.Terminal
        assertEquals("failed", terminal.state)
        assertEquals("python broken.py", terminal.command)

        val approval = OrionMessageContent.fromLegacy("note", "[TASK:APPROVAL]\\nInstall the extension?")
        assertTrue(approval.blocks.single() is OrionMessageBlock.Approval)
    }

    @Test fun userTextIsNeverReinterpretedAsAnExecutionBlock() {
        val raw = "[SHELL:FAILED]\\n$ rm -rf /"
        val content = OrionMessageContent.fromLegacy("me", raw)
        assertEquals(OrionMessageBlock.Text(raw), content.blocks.single())
    }
}
