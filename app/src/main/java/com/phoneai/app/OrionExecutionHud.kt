package com.phoneai.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableLongStateOf
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

/**
 * Linear-grade execution HUD: the last few steps Orion took, live, in plain words. Tap a step to see the
 * full output (code and command results live here, never in the chat) and jump to the page that owns it.
 */
@Composable
internal fun OrionExecutionHud(modifier: Modifier = Modifier) {
    val steps = OrionRunLog.steps
    var open by remember { mutableStateOf<OrionRunLog.Step?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = OrionRunLog.runningCount(steps)
    LaunchedEffect(running) { while (running > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(250) } }
    val pulse by rememberInfiniteTransition(label = "hud").animateFloat(.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    AnimatedVisibility(steps.isNotEmpty(), enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        val shown = steps.takeLast(if (expanded) 12 else 3).reversed()
        Column(
            modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xE60E0E10)).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)
        ) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("EXECUTION", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Text(if (running > 0) "$running running" else "${steps.count { it.status == OrionRunLog.Status.DONE }} done" +
                    steps.count { it.status == OrionRunLog.Status.FAILED }.let { if (it > 0) " · $it failed" else "" },
                    color = OrionUiPalette.secondaryText, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▾" else "▸", color = OrionUiPalette.muted, fontSize = 11.sp)
            }
            shown.forEach { s ->
                val tone = when (s.status) {
                    OrionRunLog.Status.RUNNING -> OrionUiPalette.accent
                    OrionRunLog.Status.DONE -> Color(0xFFB8CDBD)
                    OrionRunLog.Status.FAILED -> OrionUiPalette.danger
                }
                Row(Modifier.fillMaxWidth().clickable { open = s }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(tone.copy(alpha = if (s.status == OrionRunLog.Status.RUNNING) pulse else 1f)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.detail.isNotBlank()) Text(s.detail, color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(OrionRunLog.durationLabel(OrionRunLog.durationMs(s, now)), color = OrionUiPalette.muted, fontSize = 10.sp)
                }
            }
        }
    }
    open?.let { s -> OrionStepDetail(s, onDismiss = { open = null }) }
}

@Composable
private fun OrionStepDetail(s: OrionRunLog.Step, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val page = s.node?.let { OrionNodes.pageFor(it) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(OrionUiPalette.panel)
                .border(1.dp, OrionUiPalette.border, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(s.title, color = OrionUiPalette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${s.status.name.lowercase()} · ${OrionRunLog.durationLabel(OrionRunLog.durationMs(s, System.currentTimeMillis()))} · ${s.tool}", color = OrionUiPalette.muted, fontSize = 11.sp)
            if (s.detail.isNotBlank()) Text(s.detail, color = OrionUiPalette.secondaryText, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF050506)).padding(10.dp)) {
                SelectionContainer {
                    Text(s.output.ifBlank { "Still running…" }, color = OrionUiPalette.text, fontSize = 11.sp, lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page != null) Text("Open ${OrionPages.find(page)?.title ?: page} ↗", color = OrionUiPalette.text, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(OrionUiPalette.raised).clickable { OrionPages.open(context, page); onDismiss() }.padding(horizontal = 14.dp, vertical = 8.dp))
                Text("Close", color = OrionUiPalette.secondaryText, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** Raycast-grade access: typing "/" in the composer lists every page; tap to jump there. */
@Composable
internal fun OrionSlashPalette(draft: String, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val active = draft.startsWith("/")
    AnimatedVisibility(active, enter = fadeIn(tween(120)) + expandVertically(tween(150)), exit = fadeOut(tween(100)) + shrinkVertically(tween(100))) {
        val hits = OrionPages.search(draft.drop(1)).filter { it.id != "chat" }.take(6)
        Column(
            modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)
        ) {
            if (hits.isEmpty()) Text("No page matches", color = OrionUiPalette.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            hits.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { OrionPages.open(context, p.id); onPick() }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(p.about, color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("↗", color = OrionUiPalette.muted, fontSize = 12.sp)
                }
            }
        }
    }
}
