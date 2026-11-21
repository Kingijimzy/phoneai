package com.phoneai.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Pure helpers for the run sheet so they can be unit tested. */
internal object OrionRunSheetText {
    /** The command a step ran: its args' command/cmd/script when present, else its detail, else its title. */
    fun commandOf(step: OrionRunLog.Step): String {
        val fromArgs = runCatching { org.json.JSONObject(step.args) }.getOrNull()?.let { a ->
            listOf("command", "cmd", "script", "query", "url", "path", "file").map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
        }
        return fromArgs ?: step.detail.ifBlank { step.title }
    }
    fun headline(steps: List<OrionRunLog.Step>): String {
        val n = steps.size
        return when { n == 0 -> "No steps yet"; n == 1 -> "Ran 1 step"; else -> "Ran $n steps" }
    }
    fun seconds(step: OrionRunLog.Step, now: Long): Int = (((if (step.endedAt > 0L) step.endedAt else now) - step.startedAt).coerceAtLeast(0L) / 1000).toInt()
    /** Share of the slowest step, 0..1, for the timeline's duration bars. */
    fun share(step: OrionRunLog.Step, steps: List<OrionRunLog.Step>, now: Long): Float {
        val max = steps.maxOfOrNull { seconds(it, now) }?.coerceAtLeast(1) ?: 1
        return (seconds(step, now).toFloat() / max).coerceIn(0.04f, 1f)
    }
    fun links(step: OrionRunLog.Step): List<String> = OrionActivityHub.sources(step.output, 4)
}

private val Terminal = Color(0xFF050605)
private val Phosphor = Color(0xFF8CE6A0)
private val Cyan = Color(0xFF6FD6E8)

/**
 * Orion Computer: the live view of everything Orion is doing. A terminal player that follows the running step (scrub back
 * and forth through earlier ones) and a timeline of the whole run with duration bars, lane glyphs and links to the real
 * Code Studio page, file or site behind each step. Model and first-word timing ride in the header.
 */
@Composable
internal fun OrionRunSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val steps = OrionRunLog.currentRun()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var timeline by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }   // null = follow the live step
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500L) } }
    val liveIndex = steps.indexOfLast { it.status == OrionRunLog.Status.RUNNING }.takeIf { it >= 0 } ?: (steps.size - 1)
    val index = (picked ?: liveIndex).coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val step = steps.getOrNull(index)
    val pulse by rememberInfiniteTransition(label = "sheet").animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "p")
    val metrics = OrionLiveMetrics.state
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scan by rememberInfiniteTransition(label = "scan").animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "scan")

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(shown, enter = slideInVertically(tween(340)) { it / 2 } + fadeIn(tween(300))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(OrionUiPalette.panel)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OrionOrb(Modifier.size(40.dp), busy = metrics.active)
                Column(Modifier.weight(1f)) {
                    Text("ORION COMPUTER", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text(OrionRunSheetText.headline(steps) + if (metrics.active) " · live" else "", color = OrionUiPalette.secondaryText, fontSize = 12.sp)
                }
                Text("CLOSE", color = OrionUiPalette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            val chips = OrionLiveMetrics.chips(metrics, now)
            if (chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip ->
                    Text(chip, color = if (chip.endsWith("failed")) OrionUiPalette.danger else OrionUiPalette.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(OrionUiPalette.raised).border(1.dp, OrionUiPalette.border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TERMINAL" to false, "TIMELINE" to true).forEach { (label, tl) ->
                    Text(label, color = if (timeline == tl) OrionUiPalette.background else OrionUiPalette.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (timeline == tl) Cyan else OrionUiPalette.raised).clickable { timeline = tl }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
                if (step == null) Text("Nothing has run yet. Ask Orion to do something and watch it work here.", color = OrionUiPalette.muted, fontSize = 13.sp)
                else if (!timeline) Crossfade(targetState = index, animationSpec = tween(220), label = "step") { idx ->
                    val st = steps.getOrNull(idx) ?: step
                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Terminal).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(14.dp)) {
                        if (st.status == OrionRunLog.Status.RUNNING) Box(Modifier.fillMaxWidth(0.25f).height(2.dp).graphicsLayer { translationX = scan * size.width * 4f; alpha = 0.8f }.background(Cyan))
                        Text("$ " + OrionRunSheetText.commandOf(st), color = Color(0xFFEDEDED), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(st.output.ifBlank { if (st.status == OrionRunLog.Status.RUNNING) "running…" else "(no output)" }.takeLast(6000) + if (st.status == OrionRunLog.Status.RUNNING && pulse > 0.6f) " ▍" else "", color = Phosphor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp))
                        OrionRunSheetText.links(st).forEach { url ->
                            Text(url, color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { openActivityTarget(context, OrionActivityHub.Target.Url(url)) }.padding(top = 6.dp))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        steps.forEachIndexed { i, s ->
                            val lane = OrionActivityHub.lane(s)
                            val tone = if (s.status == OrionRunLog.Status.FAILED) OrionUiPalette.danger else orionLaneColor(lane)
                            Row(Modifier.fillMaxWidth().animateContentSize(tween(250)).clickable { picked = i; timeline = false }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(26.dp).graphicsLayer { val k = if (s.status == OrionRunLog.Status.RUNNING) 0.9f + 0.15f * pulse else 1f; scaleX = k; scaleY = k }.clip(CircleShape).border(1.dp, tone.copy(alpha = if (s.status == OrionRunLog.Status.RUNNING) pulse else 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                                        Text(lane.glyph, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (i < steps.size - 1) Box(Modifier.width(1.dp).height(22.dp).background(OrionUiPalette.border))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(s.title, color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(OrionRunSheetText.commandOf(s), color = OrionUiPalette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(OrionUiPalette.raised)) {
                                            Box(Modifier.fillMaxHeight().fillMaxWidth(animateFloatAsState(OrionRunSheetText.share(s, steps, now), tween(500), label = "bar").value).background(tone.copy(alpha = 0.8f)))
                                        }
                                        Text("${lane.label.lowercase()} · ${OrionRunSheetText.seconds(s, now)}s", color = OrionUiPalette.muted, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (step != null) {
                val target = OrionActivityHub.target(step)
                val tone = when (step.status) { OrionRunLog.Status.RUNNING -> Cyan; OrionRunLog.Status.DONE -> Phosphor; OrionRunLog.Status.FAILED -> OrionUiPalette.danger }
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(tone.copy(alpha = if (step.status == OrionRunLog.Status.RUNNING) pulse else 1f)))
                        Column(Modifier.weight(1f)) {
                            Text("Orion used " + OrionActivityHub.lane(step).label.lowercase().replaceFirstChar { it.uppercase() }, color = OrionUiPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(step.title, color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (target != OrionActivityHub.Target.None) Text("OPEN " + targetLabel(target).uppercase().take(26), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Cyan.copy(alpha = 0.6f), RoundedCornerShape(50)).clickable { openActivityTarget(context, target) }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("◀", color = if (index > 0) OrionUiPalette.text else OrionUiPalette.muted, fontSize = 20.sp, modifier = Modifier.clickable(enabled = index > 0) { picked = index - 1 }.padding(12.dp))
                        Text("${index + 1} / ${steps.size}" + if (picked == null) "  ·  following" else "", color = OrionUiPalette.secondaryText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("▶", color = if (index < steps.size - 1) OrionUiPalette.text else OrionUiPalette.muted, fontSize = 20.sp,
                            modifier = Modifier.clickable(enabled = index < steps.size - 1) { picked = if (index + 1 >= liveIndex) null else index + 1 }.padding(12.dp))
                    }
                }
            }
        }
        }
    }
}
