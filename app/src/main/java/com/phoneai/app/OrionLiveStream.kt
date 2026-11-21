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

/** The thinking loop made visible: eight nodes that fill as the turn moves, a pulse running toward the current one. */
@Composable
internal fun OrionStageRibbon(reached: Set<OrionEntityLoop.Stage>, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "ribbon").animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "flow")
    val stages = OrionEntityLoop.Stage.values()
    val current = stages.lastOrNull { it in reached } ?: OrionEntityLoop.Stage.OBSERVE
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        stages.forEachIndexed { i, st ->
            val on = st in reached; val c = orionStageColor(st); val isNow = st == current
            Box(Modifier.size(10.dp).graphicsLayer { val k = if (isNow) 1f + 0.35f * kotlin.math.sin(t * 6.2832f) else 1f; scaleX = k; scaleY = k }
                .clip(CircleShape).background(if (on) c else Color.Transparent).border(1.dp, c.copy(alpha = if (on) 1f else 0.35f), CircleShape))
            if (i < stages.size - 1) {
                val next = stages[i + 1] in reached
                Box(Modifier.weight(1f).height(2.dp).padding(horizontal = 2.dp)) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(OrionUiPalette.border))
                    if (on) Box(Modifier.fillMaxWidth().height(2.dp).background(c.copy(alpha = if (next) 0.9f else 0.45f)))
                    if (on && !next) Box(Modifier.fillMaxWidth(0.3f).height(2.dp).graphicsLayer { translationX = t * size.width * 3f }.background(c))
                }
            }
        }
    }
}

/** Reasoning wave: bars that breathe out of phase, so "thinking" is a living thing rather than a word. */
@Composable
internal fun OrionThinkingWave(tint: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "w")
    Canvas(modifier.size(width = 54.dp, height = 22.dp)) {
        val n = 9; val gap = size.width / n
        for (i in 0 until n) {
            val h = size.height * (0.25f + 0.7f * (0.5f + 0.5f * kotlin.math.sin((t + i * 0.11f) * 6.2832f)))
            drawLine(tint.copy(alpha = 0.55f + 0.45f * (h / size.height)), Offset(gap * (i + 0.5f), (size.height - h) / 2), Offset(gap * (i + 0.5f), (size.height + h) / 2), strokeWidth = gap * 0.5f, cap = StrokeCap.Round)
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
            OrionStageRibbon(reached, Modifier.padding(start = 40.dp, end = 6.dp, bottom = 4.dp))
            OrionStreamText.trail(steps).forEach { Crossfade(it, animationSpec = tween(300), label = "trail") { t -> Text(t, color = OrionUiPalette.muted.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 40.dp)) } }
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = steps.isNotEmpty()) { sheet = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrionOrb(Modifier.size(32.dp).clickable { hud = true }, busy = busy, mode = mode)
            if (busy || running != null) {
                if (running != null) OrionActivityGlyph(OrionActivityHub.lane(running), true)
                else OrionThinkingWave(orionStageColor(reached.lastOrNull() ?: OrionEntityLoop.Stage.OBSERVE))
                val line = running?.let { OrionStreamText.nowLine(it) } ?: orionWorkingPhrase(status)
                Crossfade(line, animationSpec = tween(260), label = "now", modifier = Modifier.weight(1f)) { shownLine -> Text(shownLine + if (caret > 0.5f) " ▍" else "", color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (running != null) FontFamily.Monospace else FontFamily.Default) }
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
