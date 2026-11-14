package com.phoneai.app

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A cited web source shown beneath an answer (Perplexity-style evidence row). */
internal data class OrionSource(val url: String, val domain: String)

private val SOURCE_URL = Regex("""https?://[^\s<>()\[\]"']+""")

/** Distinct http(s) links in an answer, in order of first appearance, trimmed of trailing punctuation. */
internal fun orionSourcesIn(text: String, max: Int = 6): List<OrionSource> {
    val seen = LinkedHashMap<String, OrionSource>()
    for (m in SOURCE_URL.findAll(text)) {
        val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', '*')
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        if (host.isBlank() || !host.contains('.')) continue
        if (seen.putIfAbsent(url, OrionSource(url, host)) == null && seen.size >= max) break
    }
    return seen.values.toList()
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OrionSourcesRow(text: String, onOpen: (String) -> Unit) {
    val sources = remember(text) { orionSourcesIn(text) }
    if (sources.isEmpty()) return
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SOURCES", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEachIndexed { i, s ->
                Row(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(OrionUiPalette.panel)
                        .border(1.dp, OrionUiPalette.border, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button) { onOpen(s.url) }
                        .semantics { contentDescription = "Open source ${i + 1}: ${s.domain}"; role = Role.Button }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(OrionUiPalette.borderStrong), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = OrionUiPalette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(s.domain, color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
