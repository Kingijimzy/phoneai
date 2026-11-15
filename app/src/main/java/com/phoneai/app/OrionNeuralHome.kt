package com.phoneai.app

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Ink = Color(0xFF000000)
private val Silver = OrionUiPalette.accent
private val Link = Color(0xFFB8CDBD)

/**
 * Default home: Orion's orb at the centre, every capability as a node on a ring.
 * Linked nodes are tied to the orb; a node glows and pulses while Orion is really using it.
 */
@Composable
internal fun OrionNeuralHome(
    lines: List<OrionChatLine>,
    activityEvents: List<OrionActivityEvent>,
    busy: Boolean,
    statusLabel: String,
    orbMode: OrbView.Mode,
    orbMicLevel: Float,
    orbSpeechAmplitude: Float,
    orbWordPulse: Float,
    onVoice: () -> Unit,
    onActivity: () -> Unit,
    onOpenTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val socialIds = remember(lines.size) { runCatching { OrionSocialConnector.connectedIds(context) }.getOrDefault(emptyList()) }
    val linked = remember(socialIds) { OrionNodes.linked(socialIds) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busy) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(if (busy) 400L else 1500L) }
    }
    val transition = rememberInfiniteTransition(label = "neural")
    val flow by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    val breathe by transition.animateFloat(.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val pulses = OrionNodes.pulses
    val lastUser = lines.lastOrNull { it.kind == "me" }?.text.orEmpty()
    val lastReply = lines.lastOrNull { it.kind == "ai" }?.text.orEmpty()
    val task = OrionActivityTimeline.latestChatCard(activityEvents)

    Column(modifier.background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NEURAL NETWORK", color = OrionUiPalette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(OrionNodes.headline(linked), color = OrionUiPalette.muted, fontSize = 11.sp)
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, OrionUiPalette.border, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Link.copy(alpha = if (busy) breathe else .9f)))
                Spacer(Modifier.width(6.dp))
                Text("LIVE", color = OrionUiPalette.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("Activity", color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.pressScale(0.94f).clip(RoundedCornerShape(50)).background(OrionUiPalette.raised)
                    .border(1.dp, OrionUiPalette.border, RoundedCornerShape(50)).clickable { onActivity() }
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val nodeR = with(density) { 21.dp.toPx() }
            val cx = wPx / 2f
            val cy = hPx * .46f
            val rx = wPx / 2f - nodeR - with(density) { 10.dp.toPx() }
            val ry = (hPx * .46f - nodeR - with(density) { 6.dp.toPx() }).coerceAtLeast(nodeR * 2)
            val pts = OrionNodes.all.indices.map { OrionNodes.ringPoint(it, OrionNodes.all.size, cx, cy, rx, ry) }

            Canvas(Modifier.fillMaxSize()) {
                OrionNodes.all.forEachIndexed { i, n ->
                    val (px, py) = pts[i]
                    val on = n.id in linked
                    val hot = OrionNodes.isHot(pulses[n.id], now)
                    if (on) {
                        val a = if (hot) .85f else .28f
                        drawLine(Link.copy(alpha = a), Offset(cx, cy), Offset(px, py), strokeWidth = if (hot) 2.2f else 1.1f, cap = StrokeCap.Round)
                        // A packet travelling along the link: toward the node while it is working.
                        val f = if (hot || busy) (flow + i * .13f) % 1f else -1f
                        if (f >= 0f) {
                            val t = if (hot) f else 1f - f
                            drawCircle(Link.copy(alpha = if (hot) .95f else .35f), if (hot) 3.6f else 2.2f, Offset(cx + (px - cx) * t, cy + (py - cy) * t))
                        }
                    }
                }
            }
            OrionOrb(
                Modifier.size(150.dp).align(Alignment.TopStart)
                    .offset { IntOffset((cx - with(density) { 75.dp.toPx() }).roundToInt(), (cy - with(density) { 75.dp.toPx() }).roundToInt()) }
                    .clickable { onVoice() },
                level = orbMicLevel, mode = orbMode, speechAmplitude = orbSpeechAmplitude, wordPulse = orbWordPulse
            )
            OrionNodes.all.forEachIndexed { i, n ->
                val (px, py) = pts[i]
                val on = n.id in linked
                val p = pulses[n.id]
                val hot = OrionNodes.isHot(p, now)
                Column(
                    Modifier.align(Alignment.TopStart).offset { IntOffset((px - nodeR * 1.6f).roundToInt(), (py - nodeR).roundToInt()) }.width(with(density) { (nodeR * 3.2f).toDp() }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(42.dp)) {
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(if (hot) Color(0xFF1F2A24) else OrionUiPalette.panel)
                                .border(if (hot) 1.6.dp else 1.dp, if (hot) Link else if (on) OrionUiPalette.borderStrong else OrionUiPalette.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(n.glyph, color = if (on) OrionUiPalette.text else OrionUiPalette.muted.copy(alpha = .6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (p != null && p.count > 0) {
                            Text(if (p.count > 99) "99+" else p.count.toString(), color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(if (p.failed) OrionUiPalette.danger else Silver)
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Text(n.label, color = if (on) OrionUiPalette.secondaryText else OrionUiPalette.muted.copy(alpha = .55f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            // Floating task card, bottom-left over the ring's lower edge.
            task?.let { ev ->
                OrionNeuralTaskCard(ev, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 6.dp).width(maxWidth * .62f), onOpenTask)
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lastUser.isNotBlank()) Text("You: " + lastUser.replace('\n', ' '), color = OrionUiPalette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                lastReply.ifBlank { "I'm here. Tap the orb to talk, or type below." },
                color = OrionUiPalette.text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis
            )
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(OrionUiPalette.raised).border(1.dp, OrionUiPalette.border, RoundedCornerShape(50))
                    .clickable { onVoice() }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Link.copy(alpha = if (busy || orbMode != OrbView.Mode.IDLE) breathe else .5f)))
                Spacer(Modifier.width(8.dp))
                Text(statusLabel, color = OrionUiPalette.secondaryText, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.size(6.dp))
    }
}

@Composable
private fun OrionNeuralTaskCard(ev: OrionActivityEvent, modifier: Modifier, onOpen: () -> Unit) {
    val done = ev.status == OrionActivityStatus.SUCCEEDED
    val failed = ev.status == OrionActivityStatus.FAILED
    val running = ev.status == OrionActivityStatus.ACTIVE || ev.status == OrionActivityStatus.WAITING
    val chip = when { done -> "COMPLETED"; failed -> "FAILED"; running -> "RUNNING"; else -> "STOPPED" }
    val tone = when { done -> Link; failed -> OrionUiPalette.danger; running -> Silver; else -> OrionUiPalette.muted }
    val shimmer by rememberInfiniteTransition(label = "task").animateFloat(.2f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s")
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE60E0E10)).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp))
            .clickable { onOpen() }.padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ev.title, color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(chip, color = tone, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, tone.copy(alpha = .5f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
        if (ev.summary.isNotBlank()) Text(ev.summary, color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(OrionUiPalette.border)) {
            Box(Modifier.fillMaxWidth(if (running) .55f else 1f).height(3.dp).clip(RoundedCornerShape(50))
                .background(tone.copy(alpha = if (running) shimmer else .9f)))
        }
        Row {
            Text(if (running) "Working" else "Finished", color = OrionUiPalette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Open ↗", color = OrionUiPalette.secondaryText, fontSize = 10.sp)
        }
    }
}
