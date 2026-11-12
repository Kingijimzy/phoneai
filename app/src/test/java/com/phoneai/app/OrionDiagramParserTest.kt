package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrionDiagramParserTest {
    @Test fun parsesVerticalMermaidFlowchartNodesAndEdges() {
        val model = parseOrionDiagram(
            """```mermaid
flowchart TD
    A[Read screen] --> B[Find object]
    B --> C[Circle object]
```"""
        )
        assertNotNull(model)
        assertEquals("TD", model!!.direction)
        assertEquals(listOf("Read screen", "Find object", "Circle object"), model.nodes.map { it.label })
        assertEquals(2, model.edges.size)
    }

    @Test fun parsesHorizontalDecisionNodes() {
        val model = parseOrionDiagram(
            """```mermaid
flowchart LR
    A{Target found?} --> B[Draw circle]
```"""
        )
        assertNotNull(model)
        assertEquals("LR", model!!.direction)
        assertEquals("Target found?", model.nodes.first().label)
        assertEquals(1, model.edges.size)
    }

    @Test fun ignoresOrdinaryTextAndMalformedDiagrams() {
        assertNull(parseOrionDiagram("This is a normal explanation."))
        assertNull(parseOrionDiagram("```mermaid\nflowchart TD\nnot a node\n```"))
    }
}
