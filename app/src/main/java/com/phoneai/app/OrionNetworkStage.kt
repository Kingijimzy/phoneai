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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Ink = Color(0xFF000000)
private val Link = Color(0xFFB8CDBD)
private val Silver = OrionUiPalette.accent

/**
 * The network stage: Orion's orb in the middle, linked capabilities close around it joined by living links,
 * unlinked ones faded on the outside. Every node is a real route; it lights when Orion really uses it.
 */
@Composable
internal fun OrionNetworkStage(
    linked: Set<String>,
    busy: Boolean,
    orbMode: OrbView.Mode,
    orbMicLevel: Float,
    orbSpeechAmplitude: Float,
    orbWordPulse: Float,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busy) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(if (busy) 300L else 1200L) } }
    val transition = rememberInfiniteTransition(label = "network")
    val driftState = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val flowState = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    val breatheState = transition.animateFloat(.5f, 1f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "breathe")
    val pulses = OrionNodes.pulses
    val order = remember { OrionNodes.all.map { it.id } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val nodeR = with(density) { 22.dp.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        val labelRoom = with(density) { 14.dp.toPx() }
        val base = remember(wPx, hPx, linked) { OrionNodes.layout(order, linked, wPx, hPx - labelRoom, nodeR, margin) }
        val orbD = OrionNodes.orbDiameter(wPx, hPx - labelRoom, nodeR, margin, with(density) { 8.dp.toPx() }).coerceIn(with(density) { 96.dp.toPx() }, with(density) { 190.dp.toPx() })
        val cx = wPx / 2f
        val cy = (hPx - labelRoom) / 2f
        val dr = with(density) { 3.dp.toPx() }
        fun at(i: Int, id: String): Offset {
            val b = base[id] ?: return Offset(cx, cy)
            val a = driftState.value * 2f * PI.toFloat() + i * 1.7f
            return Offset(b.first + sin(a) * dr, b.second + cos(a * 1f + i) * dr)
        }

        Canvas(Modifier.fillMaxSize()) {
            // faint web between neighbouring linked nodes
            val lk = order.filter { it in linked && base.containsKey(it) }
            lk.forEachIndexed { k, id ->
                val nxt = lk[(k + 1) % lk.size]
                if (nxt != id) drawLine(Link.copy(alpha = .07f), at(order.indexOf(id), id), at(order.indexOf(nxt), nxt), strokeWidth = 1f)
            }
            // glow behind the orb
            drawCircle(Brush.radialGradient(listOf(Link.copy(alpha = .10f + .10f * breatheState.value * (if (busy) 1f else .4f)), Color.Transparent), Offset(cx, cy), orbD * 1.1f), orbD * 1.1f, Offset(cx, cy))
            order.forEachIndexed { i, id ->
                if (id !in linked) return@forEachIndexed
                val p = at(i, id)
                val hot = OrionNodes.isHot(pulses[id], now)
                val bend = ((i % 2) * 2 - 1) * 26f
                val mx = (cx + p.x) / 2f - (p.y - cy) / 12f * bend / 10f
                val my = (cy + p.y) / 2f + (p.x - cx) / 12f * bend / 10f
                val path = Path().apply { moveTo(cx, cy); quadraticBezierTo(mx, my, p.x, p.y) }
                if (hot) drawPath(path, Link.copy(alpha = .18f), style = Stroke(width = 9f, cap = StrokeCap.Round))
                drawPath(path, Link.copy(alpha = if (hot) .95f else .34f), style = Stroke(width = if (hot) 2.8f else 1.5f, cap = StrokeCap.Round))
                if (hot || busy) {
                    val f = (flowState.value + i * .17f) % 1f
                    val t = if (hot) f else 1f - f
                    val u = 1f - t
                    val q = Offset(u * u * cx + 2 * u * t * mx + t * t * p.x, u * u * cy + 2 * u * t * my + t * t * p.y)
                    drawCircle(Link.copy(alpha = if (hot) 1f else .4f), if (hot) 4.2f else 2.4f, q)
                }
                if (hot) drawCircle(Brush.radialGradient(listOf(Link.copy(alpha = .38f), Color.Transparent), p, nodeR * 2.2f), nodeR * 2.2f, p)
            }
        }
        OrionOrb(
            Modifier.size(with(density) { orbD.toDp() }).align(Alignment.TopStart)
                .offset { IntOffset((cx - orbD / 2f).roundToInt(), (cy - orbD / 2f).roundToInt()) }
                .clickable { onVoice() },
            level = orbMicLevel, mode = orbMode, speechAmplitude = orbSpeechAmplitude, wordPulse = orbWordPulse
        )
        order.forEachIndexed { i, id ->
            val n = OrionNodes.all.first { it.id == id }
            val on = id in linked
            val p = pulses[id]
            val hot = OrionNodes.isHot(p, now)
            Column(
                Modifier.align(Alignment.TopStart)
                    .offset { val o = at(i, id); IntOffset((o.x - nodeR * 1.7f).roundToInt(), (o.y - nodeR).roundToInt()) }
                    .width(with(density) { (nodeR * 3.4f).toDp() })
                    .clickable { OrionPages.open(context, OrionNodes.pageFor(id)) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(44.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(if (hot) Color(0xFF16201A) else OrionUiPalette.panel.copy(alpha = if (on) 1f else .55f))
                            .border(if (hot) 1.6.dp else 1.dp, if (hot) Link else if (on) OrionUiPalette.borderStrong else OrionUiPalette.border.copy(alpha = .5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(n.glyph, color = if (on) OrionUiPalette.text else OrionUiPalette.muted.copy(alpha = .5f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (p != null && p.count > 0) {
                        Text(if (p.count > 99) "99+" else p.count.toString(), color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(if (p.failed) OrionUiPalette.danger else Silver).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
                Text(n.label, color = if (on) OrionUiPalette.secondaryText else OrionUiPalette.muted.copy(alpha = .5f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** The floating task card, as a docked strip: current run, "n of m steps", progress, the nodes it touched, Open. */
@Composable
internal fun OrionTaskStrip(title: String, run: List<OrionRunLog.Step>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (run.isEmpty()) return
    val (done, total, failed) = OrionRunLog.progress(run)
    val running = OrionRunLog.runningCount(run) > 0
    val chip = OrionPanelText.chip(done, total, failed, running)
    val tone = when { running -> Silver; failed > 0 -> OrionUiPalette.danger; else -> Link }
    val shimmer by rememberInfiniteTransition(label = "task").animateFloat(.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    val nodes = run.mapNotNull { it.node }.distinct().take(6)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E0E10))
            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(16.dp)).clickable { onOpen() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(OrionUiPalette.raised), contentAlignment = Alignment.Center) {
                Text(OrionNodes.all.firstOrNull { it.id == run.last().node }?.glyph ?: "✦", color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { run.last().title }, color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(OrionPanelText.stepsLabel(done, total), color = OrionUiPalette.muted, fontSize = 11.sp)
            }
            Text(chip, color = tone, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, tone.copy(alpha = .55f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(OrionUiPalette.border)) {
            val frac = if (total == 0) 0f else (done.toFloat() / total).coerceIn(.06f, 1f)
            Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(RoundedCornerShape(50)).background(tone.copy(alpha = if (running) shimmer else .95f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            nodes.forEach { id ->
                Box(Modifier.padding(end = 6.dp).size(24.dp).clip(CircleShape).border(1.dp, OrionUiPalette.border, CircleShape), contentAlignment = Alignment.Center) {
                    Text(OrionNodes.all.firstOrNull { it.id == id }?.glyph ?: "·", color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (running) "Working" else "Finished", color = OrionUiPalette.muted, fontSize = 11.sp)
            Spacer(Modifier.width(14.dp))
            Text("Open ↗", color = OrionUiPalette.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** What was said last: your line muted, Orion's reply centred, code never inline. Tap to open the full transcript. */
@Composable
internal fun OrionReplyPanel(lines: List<OrionChatLine>, onOpenTranscript: () -> Unit, modifier: Modifier = Modifier) {
    val lastUser = lines.lastOrNull { it.kind == "me" }?.text.orEmpty()
    val rawReply = lines.lastOrNull { it.kind == "ai" }?.text.orEmpty()
    val lastReply = OrionPanelText.clean(rawReply)
    val cards = androidx.compose.runtime.remember(rawReply) { OrionArtifactCards.extract(rawReply) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(lastReply.length / 40) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lastUser.isNotBlank()) Text(OrionPanelText.userLine(lastUser), color = OrionUiPalette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().heightIn(max = 132.dp).clickable { onOpenTranscript() }) {
            Text(
                lastReply.ifBlank { "I'm here. Tap the orb to talk, or type below." },
                color = OrionUiPalette.text, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
            )
        }
        if (cards.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cards.forEach { card ->
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised)
                        .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
                        .clickable { OrionArtifactCards.open(ctx, card) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        (when (card.kind) { OrionArtifactCards.Kind.CODE -> "▣ "; OrionArtifactCards.Kind.FILE -> "▤ "; OrionArtifactCards.Kind.PREVIEW -> "◉ " }) + card.title + "  ↗",
                        color = OrionUiPalette.text, fontSize = 12.sp, maxLines = 1
                    )
                }
            }
        }
    }
}

/** "Speaking. Talk to interrupt" — mic starts voice, red end stops Orion. */
@Composable
internal fun OrionStatusPill(text: String, live: Boolean, onMic: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val breathe by rememberInfiniteTransition(label = "pill").animateFloat(.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(OrionUiPalette.raised).border(1.dp, OrionUiPalette.border, RoundedCornerShape(50)).padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Link.copy(alpha = if (live) breathe else .5f)))
            Spacer(Modifier.width(9.dp))
            Text(text, color = OrionUiPalette.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onMic), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Talk", tint = OrionUiPalette.text, modifier = Modifier.size(17.dp)) }
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x33E05A5A)).clickable(onClick = onEnd), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CallEnd, "Stop", tint = Color(0xFFE88A8A), modifier = Modifier.size(16.dp)) }
        }
    }
}


/** Tappable cards for code, files and local previews found in a reply, so chat itself stays clean. */
@Composable
internal fun OrionArtifactCardRow(text: String, modifier: Modifier = Modifier) {
    val cards = androidx.compose.runtime.remember(text) { OrionArtifactCards.extract(text) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (cards.isEmpty()) return
    Row(modifier.fillMaxWidth().padding(top = 9.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { card ->
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised)
                    .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
                    .clickable { OrionArtifactCards.open(ctx, card) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    (when (card.kind) { OrionArtifactCards.Kind.CODE -> "▣ "; OrionArtifactCards.Kind.FILE -> "▤ "; OrionArtifactCards.Kind.PREVIEW -> "◉ " }) + card.title + "  ↗",
                    color = OrionUiPalette.text, fontSize = 12.sp, maxLines = 1
                )
            }
        }
    }
}
