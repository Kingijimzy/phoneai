package com.phoneai.app

internal data class OrionDiagramNode(val id: String, val label: String)
internal data class OrionDiagramEdge(val from: String, val to: String)
internal data class OrionDiagramModel(
    val direction: String,
    val nodes: List<OrionDiagramNode>,
    val edges: List<OrionDiagramEdge>
)

/** Small deterministic Mermaid flowchart subset for rich, offline chat rendering. */
internal fun parseOrionDiagram(raw: String): OrionDiagramModel? {
    val source = Regex("""(?is)```mermaid\s*(.*?)```""").find(raw)?.groupValues?.getOrNull(1)
        ?: raw.takeIf { Regex("""(?im)^\s*(?:flowchart|graph)\s+(?:TD|TB|BT|LR|RL)\s*$""").containsMatchIn(it) }
        ?: return null
    val direction = Regex("""(?im)^\s*(?:flowchart|graph)\s+(TD|TB|BT|LR|RL)\b""")
        .find(source)?.groupValues?.getOrNull(1)?.uppercase() ?: "TD"
    val nodePattern = Regex("""([A-Za-z0-9_]+)\s*(?:\[\s*([^\]]+)\s*]|\(\s*([^)]+)\s*\)|\{\s*([^}]+)\s*})""")
    val edgePattern = Regex("""([A-Za-z0-9_]+)(?:\s*(?:\[[^\]]*]|\([^)]*\)|\{[^}]*}))?\s*(?:-->|==>|-\.->)\s*([A-Za-z0-9_]+)""")
    val nodes = linkedMapOf<String, OrionDiagramNode>()
    nodePattern.findAll(source).forEach { match ->
        val id = match.groupValues[1]
        val label = (2..4).map { match.groupValues[it] }.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        nodes.putIfAbsent(id, OrionDiagramNode(id, label.ifBlank { id }))
    }
    val edges = edgePattern.findAll(source).map { match ->
        OrionDiagramEdge(match.groupValues[1], match.groupValues[2])
    }.distinct().toList()
    edges.forEach { edge ->
        if (edge.from !in nodes) nodes[edge.from] = OrionDiagramNode(edge.from, edge.from)
        if (edge.to !in nodes) nodes[edge.to] = OrionDiagramNode(edge.to, edge.to)
    }
    if (nodes.isEmpty()) return null
    return OrionDiagramModel(direction, nodes.values.toList(), edges)
}
