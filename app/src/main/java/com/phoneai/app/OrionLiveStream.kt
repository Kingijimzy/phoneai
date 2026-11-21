package com.phoneai.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pure text for the one-line live stream: no cards, just what is happening now. */
internal object OrionStreamText {
    /** The newest meaningful line of a running step's output, else its command/detail, else its title. */
    fun nowLine(step: OrionRunLog.Step): String {
        val out = step.output.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("$ ") }.lastOrNull()
        return (out ?: step.detail.ifBlank { step.title }).replace(Regex("\\s+"), " ").take(140)
    }
    /** What Orion is doing in plain words, driven by the stage it has really reached; a specific runtime status wins over the generic ones. */
    fun thinkingPhrase(reached: Set<OrionEntityLoop.Stage>, status: String): String {
        val generic = status.isBlank() || status.equals("Thinking", true) || status.startsWith("Thinking") || status.equals("Working...", true) || status.equals("Ready", true)
        if (!generic) return status.removeSuffix("…").removeSuffix("...").take(80)
        return when (OrionEntityLoop.Stage.values().lastOrNull { it in reached }) {
            null, OrionEntityLoop.Stage.OBSERVE -> "Reading the situation"
            OrionEntityLoop.Stage.RECALL -> "Recalling what matters"
            OrionEntityLoop.Stage.REASON -> "Working it out"
            OrionEntityLoop.Stage.PLAN -> "Choosing the best route"
            OrionEntityLoop.Stage.ACT -> "Doing it"
            OrionEntityLoop.Stage.VERIFY -> "Checking the result"
            OrionEntityLoop.Stage.LEARN -> "Keeping what I learned"
            OrionEntityLoop.Stage.REPORT -> "Putting it into words"
        }
    }
    fun trail(steps: List<OrionRunLog.Step>): List<String> =
        steps.filter { it.status != OrionRunLog.Status.RUNNING }.takeLast(2).map { (if (it.status == OrionRunLog.Status.FAILED) "retrying after: " else "") + it.title.take(60) }
}

/** Our palette, one colour per kind of activity, so the live line says what Orion is doing before you read it. */
internal fun orionLaneColor(lane: OrionActivityHub.Lane): Color = when (lane) {
    OrionActivityHub.Lane.CODE -> Color(0xFF6FD6E8)
    OrionActivityHub.Lane.WEB -> Color(0xFF7FB2FF)
    OrionActivityHub.Lane.MCP -> Color(0xFFB69CFF)
    OrionActivityHub.Lane.RUNTIME -> Color(0xFF8CE6A0)
    OrionActivityHub.Lane.FILES -> Color(0xFFFFB454)
    OrionActivityHub.Lane.MEMORY -> Color(0xFFE59CFF)
    OrionActivityHub.Lane.COMMS -> Color(0xFF7BE0C3)
    OrionActivityHub.Lane.PLATFORM -> Color(0xFFFF8F7A)
    OrionActivityHub.Lane.OTHER -> Color(0xFFB8CDBD)
}

internal fun orionStageColor(stage: OrionEntityLoop.Stage): Color = when (stage) {
    OrionEntityLoop.Stage.OBSERVE -> Color(0xFF6FD6E8)
    OrionEntityLoop.Stage.RECALL -> Color(0xFFB69CFF)
    OrionEntityLoop.Stage.REASON -> Color(0xFFFFB454)
    OrionEntityLoop.Stage.PLAN -> Color(0xFF7FB2FF)
    OrionEntityLoop.Stage.ACT -> Color(0xFF8CE6A0)
    OrionEntityLoop.Stage.VERIFY -> Color(0xFF7BE0C3)
    OrionEntityLoop.Stage.LEARN -> Color(0xFFE59CFF)
    OrionEntityLoop.Stage.REPORT -> Color(0xFFB8CDBD)
}

/** Grok/Claude-style thinking line: the words shimmer with a light sweeping through them, and they change as the stage changes. */
@Composable
internal fun OrionThinkingText(text: String, tint: Color, modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(-200f, 600f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "x")
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(OrionUiPalette.muted, tint, OrionUiPalette.text, tint, OrionUiPalette.muted),
        start = Offset(sweep - 160f, 0f), end = Offset(sweep + 160f, 0f))
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Crossfade(text, animationSpec = tween(350), label = "think") { shown ->
            Text(shown, style = androidx.compose.ui.text.TextStyle(brush = brush, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Activity glyph with an orbiting arc while the step runs. */
@Composable
internal fun OrionActivityGlyph(lane: OrionActivityHub.Lane, running: Boolean, modifier: Modifier = Modifier) {
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "s")
    val tint = orionLaneColor(lane)
    Box(modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(tint.copy(alpha = 0.16f))
            if (running) drawArc(tint, spin, 90f, false, Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), style = Stroke(3f, cap = StrokeCap.Round))
            else drawCircle(tint.copy(alpha = 0.6f), size.width / 2 - 2f, style = Stroke(2f))
        }
        Crossfade(lane, animationSpec = tween(220), label = "glyph") { l -> Text(l.glyph, color = orionLaneColor(l), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * The conversation is the surface; execution is one living line under it. A small orb and the current action streaming
 * in place (with the latest output line), the last couple of finished steps fading above, tap to see the live tail.
 * No card, no border, nothing to dismiss. Tap the orb for the environment HUD.
 */
@Composable
internal fun OrionLiveStream(status: String, mode: OrbView.Mode, modifier: Modifier = Modifier) {
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf(false) }
    var grade by remember { mutableStateOf(OrionConfidence.latest) }
    var reached by remember { mutableStateOf(emptySet<OrionEntityLoop.Stage>()) }
    LaunchedEffect(Unit) { while (true) { busy = OrionEntityLoop.live?.let { it.endedAt == 0L } == true; reached = OrionEntityLoop.live?.reached.orEmpty(); now = System.currentTimeMillis(); grade = OrionConfidence.latest; kotlinx.coroutines.delay(350L) } }
    val steps = OrionRunLog.currentRun()
    val running = steps.lastOrNull { it.status == OrionRunLog.Status.RUNNING }
    val confidence = OrionConfidenceView.label(grade, now)
    if (!busy && running == null && confidence == null && steps.isEmpty()) { OrionEnvironmentSheet(hud, false) { hud = false }; return }
    val caret by rememberInfiniteTransition(label = "caret").animateFloat(0.15f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "c")
    Column(modifier.fillMaxWidth().animateContentSize(tween(240)).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (busy) {
            OrionStreamText.trail(steps).forEach { Crossfade(it, animationSpec = tween(300), label = "trail") { t -> Text(t, color = OrionUiPalette.muted.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 40.dp)) } }
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = steps.isNotEmpty()) { sheet = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrionOrb(Modifier.size(32.dp).clickable { hud = true }, busy = busy, mode = mode)
            if (busy || running != null) {
                if (running != null) OrionActivityGlyph(OrionActivityHub.lane(running), true)
                if (running != null) Crossfade(OrionStreamText.nowLine(running), animationSpec = tween(260), label = "now", modifier = Modifier.weight(1f)) { shownLine -> Text(shownLine + if (caret > 0.5f) " ▍" else "", color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) }
                else OrionThinkingText(OrionStreamText.thinkingPhrase(reached, status), orionStageColor(reached.lastOrNull() ?: OrionEntityLoop.Stage.OBSERVE), Modifier.weight(1f))
                running?.let { Text("${((now - it.startedAt).coerceAtLeast(0L) / 1000)}s", color = OrionUiPalette.muted, fontSize = 10.sp) }
            } else {
                if (steps.isNotEmpty()) Text(">_ " + (if (steps.size == 1) "Ran 1 command" else "Ran ${steps.size} commands") + "  ›", color = OrionUiPalette.secondaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                confidence?.let { Text(it, color = OrionUiPalette.muted, fontSize = 10.sp) }
            }
        }
    }
    if (sheet) OrionRunSheet { sheet = false }
    OrionEnvironmentSheet(hud, busy) { hud = false }
}
