package com.phoneai.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    LaunchedEffect(Unit) { while (true) { busy = OrionEntityLoop.live?.let { it.endedAt == 0L } == true; now = System.currentTimeMillis(); grade = OrionConfidence.latest; kotlinx.coroutines.delay(700L) } }
    val steps = OrionRunLog.currentRun()
    val running = steps.lastOrNull { it.status == OrionRunLog.Status.RUNNING }
    val confidence = OrionConfidenceView.label(grade, now)
    if (!busy && running == null && confidence == null && steps.isEmpty()) { OrionEnvironmentSheet(hud, false) { hud = false }; return }
    val caret by rememberInfiniteTransition(label = "caret").animateFloat(0.15f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "c")
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (busy) OrionStreamText.trail(steps).forEach { Text(it, color = OrionUiPalette.muted.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 40.dp)) }
        Row(Modifier.fillMaxWidth().clickable(enabled = steps.isNotEmpty()) { sheet = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrionOrb(Modifier.size(32.dp).clickable { hud = true }, busy = busy, mode = mode)
            if (busy || running != null) {
                val line = running?.let { OrionStreamText.nowLine(it) } ?: orionWorkingPhrase(status)
                Text(line + if (caret > 0.5f) " ▍" else "", color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (running != null) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.weight(1f))
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
