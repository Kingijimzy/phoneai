package com.phoneai.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
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
    var world by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { grade = OrionConfidence.latest; busy = OrionEntityLoop.live?.let { it.endedAt == 0L } == true; kotlinx.coroutines.delay(1000L) } }
    LaunchedEffect(open) { while (open) { world = kotlin.runCatching { OrionWorldState.build(context) }.getOrNull(); kotlinx.coroutines.delay(2000L) } }
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OrionOrb(Modifier.size(34.dp).clickable { open = true }, busy = busy)
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
    if (open) Dialog(onDismissRequest = { open = false }) { OrionHud(world, busy) { open = false } }
}

private val HudCyan = Color(0xFF6FD6E8)

@Composable
private fun HudGlyph(kind: String, tint: Color, pulse: Float, modifier: Modifier = Modifier.size(26.dp)) {
    Canvas(modifier) {
        val w = size.width; val c = Offset(w / 2, size.height / 2); val sw = w * 0.08f
        when (kind) {
            "cerebro" -> { drawCircle(tint.copy(alpha = 0.35f + 0.4f * pulse), w * 0.42f, c, style = Stroke(sw)); drawCircle(tint, w * 0.16f, c)
                drawCircle(tint.copy(alpha = 0.6f), w * 0.30f, c, style = Stroke(sw * 0.6f)) }
            "chronos" -> { drawCircle(tint, w * 0.42f, c, style = Stroke(sw))
                drawLine(tint, c, Offset(c.x, c.y - w * 0.28f), sw, StrokeCap.Round)
                drawLine(tint.copy(alpha = 0.5f + 0.5f * pulse), c, Offset(c.x + w * 0.22f, c.y + w * 0.08f), sw, StrokeCap.Round) }
            "prometheus" -> { val p = Path().apply { moveTo(c.x, w * 0.08f); lineTo(w * 0.82f, w * 0.88f); lineTo(w * 0.18f, w * 0.88f); close() }
                drawPath(p, tint.copy(alpha = 0.55f + 0.45f * pulse), style = Stroke(sw)) }
            "workers" -> { for (i in 0..2) for (j in 0..2) drawCircle(tint.copy(alpha = if ((i + j) % 2 == 0) 0.4f + 0.6f * pulse else 0.9f), w * 0.07f, Offset(w * (0.2f + 0.3f * i), w * (0.2f + 0.3f * j))) }
            "tools" -> { drawRect(tint, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), style = Stroke(sw))
                drawLine(tint, Offset(w * 0.15f, w * 0.5f), Offset(w * 0.85f, w * 0.5f), sw); drawLine(tint, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.85f), sw) }
            "network" -> { for (i in 1..3) drawArc(tint.copy(alpha = 0.3f + 0.2f * i), 225f, 90f, false, Offset(c.x - w * 0.14f * i, w * 0.9f - w * 0.28f * i), Size(w * 0.28f * i, w * 0.28f * i), style = Stroke(sw)) }
            "mission" -> { drawCircle(tint, w * 0.4f, c, style = Stroke(sw)); drawCircle(tint, w * 0.22f, c, style = Stroke(sw)); drawCircle(tint.copy(alpha = 0.5f + 0.5f * pulse), w * 0.07f, c) }
            else -> drawCircle(tint, w * 0.3f, c, style = Stroke(sw))
        }
    }
}

@Composable
private fun HudPanel(title: String, kind: String, tint: Color, pulse: Float, line: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised).border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HudGlyph(kind, tint, pulse)
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(line.ifBlank { "no report" }, color = OrionUiPalette.text, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable
private fun OrionHud(w: org.json.JSONObject?, busy: Boolean, onClose: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "hud").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    val battery = w?.optJSONObject("battery"); val pct = battery?.optInt("percent", -1) ?: -1; val charging = battery?.optBoolean("charging") == true
    val net = w?.optString("network").orEmpty().ifBlank { "unknown" }
    val agents = w?.optJSONObject("agents"); val workers = agents?.optJSONObject("workers")
    val tools = w?.optJSONObject("tools"); val missions = w?.optJSONObject("missions"); val time = w?.optJSONObject("time")
    val batteryTint = when { pct in 0..19 && !charging -> OrionUiPalette.danger; pct in 20..39 && !charging -> OrionUiPalette.warning; else -> OrionUiPalette.success }
    val netTint = if (net == "offline") OrionUiPalette.danger else HudCyan
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(OrionUiPalette.panel).border(1.dp, HudCyan.copy(alpha = 0.25f + 0.25f * pulse), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrionOrb(Modifier.size(54.dp), busy = busy)
            Column(Modifier.weight(1f)) {
                Text("WHAT ORION SEES", color = HudCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text((if (busy) "ACTIVE" else "STANDING BY") + " · " + (time?.optString("part_of_day").orEmpty()).uppercase() + " · " + (time?.optString("iso").orEmpty().drop(11).take(5)),
                    color = OrionUiPalette.secondaryText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text("CLOSE", color = OrionUiPalette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Power and link
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised).border(1.dp, batteryTint.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (charging) "POWER · CHARGING" else "POWER", color = batteryTint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, batteryTint, RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxSize().padding(2.dp)) { Box(Modifier.fillMaxSize(((pct.coerceIn(0, 100)) / 100f).coerceAtLeast(0.02f).let { if (pct < 0) 0.02f else it }).clip(RoundedCornerShape(2.dp)).background(batteryTint.copy(alpha = if (charging) 0.55f + 0.45f * pulse else 1f))) }
                        }
                        Text(if (pct >= 0) "$pct%" else "?", color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised).border(1.dp, netTint.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudGlyph("network", netTint, pulse)
                    Column { Text("LINK", color = netTint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp); Text(net.uppercase(), color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
            HudPanel("CEREBRO · MEMORY", "cerebro", HudCyan, pulse, agents?.optString("cerebro").orEmpty())
            HudPanel("CHRONOS · TIME", "chronos", Color(0xFFB69CFF), pulse, agents?.optString("chronos").orEmpty())
            HudPanel("PROMETHEUS · GROWTH", "prometheus", Color(0xFFFFB454), pulse, agents?.optString("prometheus").orEmpty())
            HudPanel("DRONE SWARM", "workers", OrionUiPalette.success, pulse, "${workers?.optInt("running", 0) ?: 0} of ${workers?.optInt("max", 0) ?: 0} workers busy")
            HudPanel("MISSION", "mission", OrionUiPalette.accent, pulse, missions?.optString("objective").orEmpty().ifBlank { "no standing objective" } + " · ${missions?.optInt("active", 0) ?: 0} active")
            val names = remember(w == null) { runCatching { ToolRegistry.names().filter { ToolRegistry.isImplemented(it) }.sorted() }.getOrDefault(emptyList()) }
            HudPanel("ARSENAL", "tools", HudCyan, pulse, "${tools?.optInt("implemented", 0) ?: 0} usable of ${tools?.optInt("registered", 0) ?: 0} registered")
            names.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { n -> Text(n, color = OrionUiPalette.secondaryText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, OrionUiPalette.border, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            val unread = w?.optJSONArray("unreadable")
            if (unread != null && unread.length() > 0) Text("Cannot read: " + (0 until unread.length()).joinToString { unread.optString(it) }, color = OrionUiPalette.warning, fontSize = 10.sp)
        }
    }
}
