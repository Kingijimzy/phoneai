package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

internal object OrionConfidenceView {
    /** Pure: how the meter reads for a grade; hidden when there is none or it is stale. */
    fun label(g: OrionConfidence.Grade?, now: Long): String? {
        if (g == null || now - g.atMs > 30 * 60 * 1000L) return null
        return "${g.percent}% sure" + if (g.verifiedBy.isNotBlank()) " · checked by ${g.verifiedBy}" else ""
    }
}

/** "Environment" pill (tap to see the exact WorldState JSON Orion is given) and the confidence meter for the latest answer. */
@Composable
internal fun OrionWorldBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var grade by remember { mutableStateOf(OrionConfidence.latest) }
    var json by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { grade = OrionConfidence.latest; kotlinx.coroutines.delay(1000L) } }
    LaunchedEffect(open) { if (open) json = kotlin.runCatching { OrionWorldState.pretty(context) }.getOrDefault("World state could not be read.") }
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ENVIRONMENT", color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, OrionUiPalette.border, RoundedCornerShape(10.dp))
                .clickable { open = true }.padding(horizontal = 9.dp, vertical = 5.dp))
        OrionConfidenceView.label(grade, System.currentTimeMillis())?.let { label ->
            val pct = grade?.percent ?: 0
            val color = if (pct >= OrionMetacognition.VERIFY_BELOW) OrionUiPalette.success else OrionUiPalette.warning
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, color = OrionUiPalette.secondaryText, fontSize = 10.sp, maxLines = 1)
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WHAT ORION SEES", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(json, color = OrionUiPalette.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            Text("Close", color = OrionUiPalette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { open = false }.padding(vertical = 4.dp))
        }
    }
}
