package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class OrionDiffStats(val added: Int, val removed: Int, val file: String?)

/** True when the text is a unified diff (hunk headers or file headers plus changed lines). */
internal fun looksLikeUnifiedDiff(source: String): Boolean {
    val lines = source.lines()
    val hunks = lines.count { it.startsWith("@@") }
    val headers = lines.any { it.startsWith("--- ") } && lines.any { it.startsWith("+++ ") }
    val changes = lines.count { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
    return (hunks > 0 || headers) && changes > 0
}

internal fun orionDiffStats(source: String): OrionDiffStats {
    var a = 0; var r = 0; var file: String? = null
    for (l in source.lines()) when {
        l.startsWith("+++ ") -> if (file == null) file = l.removePrefix("+++ ").removePrefix("b/").substringBefore('\t').takeIf { it != "/dev/null" }
        l.startsWith("--- ") -> if (file == null) file = l.removePrefix("--- ").removePrefix("a/").substringBefore('\t').takeIf { it != "/dev/null" }
        l.startsWith("+") -> a++
        l.startsWith("-") -> r++
    }
    return OrionDiffStats(a, r, file)
}

/** Cursor-style file-change card: file name, +/- counts, tinted lines, collapsible review. */
@Composable
internal fun OrionDiffCard(source: String, filename: String?) {
    val stats = remember(source) { orionDiffStats(source) }
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(true) }
    val mono = FontFamily.Monospace
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.background)
            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.background(OrionUiPalette.panel).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(filename ?: stats.file ?: "Changes", color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text("+${stats.added}", color = OrionUiPalette.success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("−${stats.removed}", color = OrionUiPalette.danger, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("COPY", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(source)) }.padding(6.dp))
            Text(if (open) "HIDE" else "REVIEW", color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                source.lines().forEach { line ->
                    val (bg, fg) = when {
                        line.startsWith("@@") -> OrionUiPalette.panel to OrionUiPalette.muted
                        line.startsWith("+++") || line.startsWith("---") -> Color.Transparent to OrionUiPalette.muted
                        line.startsWith("+") -> OrionUiPalette.success.copy(alpha = .14f) to OrionUiPalette.text
                        line.startsWith("-") -> OrionUiPalette.danger.copy(alpha = .14f) to OrionUiPalette.text
                        else -> Color.Transparent to OrionUiPalette.secondaryText
                    }
                    Text(line.ifEmpty { " " }, color = fg, fontSize = 11.sp, fontFamily = mono, lineHeight = 16.sp,
                        modifier = Modifier.widthIn(min = 280.dp).fillMaxWidth().background(bg).padding(horizontal = 12.dp))
                }
            }
        }
    }
}
