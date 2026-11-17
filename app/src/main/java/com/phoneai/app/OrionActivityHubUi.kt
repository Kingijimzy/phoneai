package com.phoneai.app

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

internal fun openActivityTarget(c: Context, t: OrionActivityHub.Target) {
    runCatching {
        val activity = generateSequence(c) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<MainChatActivity>().firstOrNull()
        when (t) {
            is OrionActivityHub.Target.Studio -> c.startActivity(Intent(c, StudioActivity::class.java)
                .putExtra("page", t.page).also { i -> t.file?.let { i.putExtra("file", it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is OrionActivityHub.Target.Url -> {
                if (activity != null) activity.openBrowserFromActivity(t.url)
                else c.startActivity(Intent(c, MainChatActivity::class.java)
                    .putExtra(MainChatActivity.EXTRA_OPEN_BROWSER_URL, t.url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is OrionActivityHub.Target.Page -> OrionPages.open(c, t.id)
            OrionActivityHub.Target.None -> Unit
        }
    }
}

internal fun laneIcon(l: OrionActivityHub.Lane): ImageVector = when (l) {
    OrionActivityHub.Lane.CODE -> Icons.Rounded.Code
    OrionActivityHub.Lane.WEB -> Icons.Rounded.Public
    OrionActivityHub.Lane.MCP -> Icons.Rounded.Cable
    OrionActivityHub.Lane.RUNTIME -> Icons.Rounded.Terminal
    OrionActivityHub.Lane.FILES -> Icons.Rounded.FolderOpen
    OrionActivityHub.Lane.MEMORY -> Icons.Rounded.Psychology
    OrionActivityHub.Lane.COMMS -> Icons.Rounded.Chat
    OrionActivityHub.Lane.PLATFORM -> Icons.Rounded.Extension
    OrionActivityHub.Lane.OTHER -> Icons.Rounded.MoreHoriz
}

private fun targetLabel(t: OrionActivityHub.Target): String = when (t) {
    is OrionActivityHub.Target.Studio -> "Code Studio · " + (t.file?.substringAfterLast('/') ?: t.page)
    is OrionActivityHub.Target.Url -> OrionActivityHub.shortUrl(t.url)
    is OrionActivityHub.Target.Page -> OrionPages.find(t.id)?.title ?: t.id
    OrionActivityHub.Target.None -> ""
}

@Composable
private fun statusTone(s: OrionRunLog.Status): Color = when (s) {
    OrionRunLog.Status.RUNNING -> OrionUiPalette.accent
    OrionRunLog.Status.DONE -> Color(0xFFB8CDBD)
    OrionRunLog.Status.FAILED -> OrionUiPalette.danger
}

@Composable
private fun Chip(text: String, onClick: (() -> Unit)?, tint: Color = OrionUiPalette.text, icon: ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(OrionUiPalette.raised)
        .let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = tint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Each kind of work shows itself differently: web as sources, code as files, runtime as a terminal. */
@Composable
private fun StepVisual(step: OrionRunLog.Step, now: Long) {
    val c = LocalContext.current
    val lane = OrionActivityHub.lane(step)
    val target = OrionActivityHub.target(step)
    Column(Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (lane) {
            OrionActivityHub.Lane.WEB -> {
                val links = (listOfNotNull((target as? OrionActivityHub.Target.Url)?.url) + OrionActivityHub.sources(step.output)).distinct().take(6)
                if (links.isEmpty()) Text(step.detail.ifBlank { "Reading the web…" }, color = OrionUiPalette.muted, fontSize = 10.sp)
                links.forEach { u -> Chip(OrionActivityHub.shortUrl(u), { openActivityTarget(c, OrionActivityHub.Target.Url(u)) }, icon = Icons.Rounded.Link) }
            }
            OrionActivityHub.Lane.CODE, OrionActivityHub.Lane.FILES -> {
                (target as? OrionActivityHub.Target.Studio)?.file?.let { f -> Chip(f, { openActivityTarget(c, target) }, icon = Icons.Rounded.FolderOpen) }
                Mono(step.output.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n").ifBlank { "Working…" })
            }
            OrionActivityHub.Lane.RUNTIME -> {
                Mono("$ " + step.detail + "\n" + step.output.trim().takeLast(320).ifBlank { if (step.status == OrionRunLog.Status.RUNNING) "running…" else "" })
            }
            OrionActivityHub.Lane.MCP -> {
                val a = OrionActivityHub.parse(step.args)
                Chip(listOfNotNull(a?.optString("id")?.takeIf { it.isNotBlank() }, a?.optString("tool")?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "MCP server" }, null, icon = Icons.Rounded.Cable)
                Mono(step.output.trim().take(260).ifBlank { "Waiting for the server…" })
            }
            else -> if (step.output.isNotBlank()) Mono(step.output.trim().take(260))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(OrionRunLog.durationLabel(OrionRunLog.durationMs(step, now)), color = OrionUiPalette.muted, fontSize = 9.sp)
            if (target != OrionActivityHub.Target.None) {
                Chip(targetLabel(target), { openActivityTarget(c, target) }, icon = Icons.Rounded.OpenInNew)
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(text, color = OrionUiPalette.secondaryText, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
            maxLines = 8, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF050506)).padding(8.dp))
    }
}

@Composable
private fun StepRow(step: OrionRunLog.Step, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalContext.current
    val target = OrionActivityHub.target(step)
    val lane = OrionActivityHub.lane(step)
    val tone = statusTone(step.status)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(10.dp))
            Icon(laneIcon(lane), null, tint = OrionUiPalette.muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (step.detail.isNotBlank()) Text(step.detail, color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (target != OrionActivityHub.Target.None) {
                Icon(Icons.Rounded.OpenInNew, "Open source", tint = OrionUiPalette.text,
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable { openActivityTarget(c, target) }.padding(9.dp))
            }
        }
        if (expanded) StepVisual(step, now)
    }
}

/** One live summary per task, updated in place. Never one message per command. */
@Composable
internal fun OrionActivityHubCard(
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
    events: List<OrionActivityEvent> = emptyList()
) {
    val run = OrionRunLog.currentRun()
    if (run.isEmpty() && events.isEmpty()) return
    val sum = run.takeIf { it.isNotEmpty() }?.let { OrionActivityHub.summarize(OrionActivityHistory.runTitle, it) }
    val latestEvent = events.maxByOrNull { it.timestampMs }
    var open by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val runningCount = sum?.running ?: 0
    LaunchedEffect(runningCount) { while (runningCount > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val tone = when {
        latestEvent?.status == OrionActivityStatus.ACTIVE -> OrionUiPalette.accent
        latestEvent?.status == OrionActivityStatus.FAILED -> OrionUiPalette.danger
        latestEvent?.status == OrionActivityStatus.WAITING -> OrionUiPalette.warning
        sum?.status == "running" -> OrionUiPalette.accent
        sum?.status == "failed" -> OrionUiPalette.danger
        sum?.status == "partial" -> OrionUiPalette.warning
        else -> Color(0xFFB8CDBD)
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(OrionUiPalette.panel)
        .border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onOpenAll() }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(latestEvent?.let(OrionActivityPresentation::title) ?: sum?.title ?: "Recent activity",
                    color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(latestEvent?.let { OrionActivityPresentation.summary(it) } ?: sum?.let {
                    "${it.done}/${it.total} steps · ${it.status.uppercase()}" + (if (it.failed > 0) " · ${it.failed} failed" else "")
                } ?: "Tap to inspect the live task stream",
                    color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("OPEN", color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sum?.lanes?.take(4)?.forEach { (lane, n) -> Chip("${lane.label} $n", null, OrionUiPalette.secondaryText, laneIcon(lane)) }
            if (events.isNotEmpty()) Chip("${events.size} updates", null, OrionUiPalette.secondaryText)
            Chip("History", onOpenAll, OrionUiPalette.text, Icons.Rounded.History)
        }
        if (latestEvent != null) {
            Text(OrionActivityPresentation.summary(latestEvent), color = OrionUiPalette.muted, fontSize = 10.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        }
        Column(Modifier.padding(top = 6.dp)) {
            run.takeLast(3).forEach { step ->
                StepRow(step, now, expanded = open == step.id || (!expanded && step.status == OrionRunLog.Status.RUNNING)) { open = if (open == step.id) null else step.id }
            }
        }
    }
}

/** Failures and anything that needs the owner, kept apart from routine progress. */
@Composable
internal fun OrionInterruptionBanner(modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val items = OrionActivityHub.interruptions(OrionRunLog.currentRun()).takeLast(2)
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1010))
        .border(1.dp, OrionUiPalette.danger.copy(alpha = .5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("NEEDS ATTENTION", color = OrionUiPalette.danger, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        items.forEach { s ->
            val t = OrionActivityHub.target(s)
            Row(Modifier.fillMaxWidth().clickable { openActivityTarget(c, t) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title + if (s.status == OrionRunLog.Status.FAILED) " failed" else "", color = OrionUiPalette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120), color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (t != OrionActivityHub.Target.None) Icon(Icons.Rounded.OpenInNew, "Open source", tint = OrionUiPalette.text, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun openActivityEvidence(c: Context, evidence: OrionActivityEvidence) {
    when (evidence) {
        is OrionActivityEvidence.Browser -> openActivityTarget(c, OrionActivityHub.Target.Url(evidence.url))
        is OrionActivityEvidence.File -> openActivityTarget(c, OrionActivityHub.Target.Studio("editor", evidence.displayPath.removePrefix("/workspace/").removePrefix("/")))
        is OrionActivityEvidence.Terminal -> openActivityTarget(c, OrionActivityHub.Target.Studio("terminal"))
        is OrionActivityEvidence.CodeDiff -> openActivityTarget(c, OrionActivityHub.Target.Studio("editor", evidence.fileLabel.removePrefix("/workspace/").removePrefix("/")))
        is OrionActivityEvidence.AgentHandoff -> openActivityTarget(c, OrionActivityHub.Target.Studio("agent"))
    }
}

/** The full hub: this task live, plus every past task, searchable and resumable. */
@Composable
internal fun OrionRunSheet(
    onDismiss: () -> Unit,
    onResume: (String) -> Unit = {},
    events: List<OrionActivityEvent> = emptyList()
) {
    val context = LocalContext.current
    var openEvent by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openEntry by remember { mutableStateOf<Long?>(null) }
    var openStep by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val live = OrionRunLog.currentRun()
    val running = OrionRunLog.runningCount(live)
    LaunchedEffect(running) { while (running > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val history = remember(tab, live.size, live.count { it.status != OrionRunLog.Status.RUNNING }) { OrionActivityHistory.load(live) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(18.dp)).padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NOW", "HISTORY").forEachIndexed { i, label ->
                    Text(label, color = if (tab == i) OrionUiPalette.text else OrionUiPalette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) OrionUiPalette.raised else Color.Transparent)
                            .clickable { tab = i }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (tab == 0) {
                    OrionInterruptionBanner(Modifier.padding(bottom = 8.dp))
                    if (live.isEmpty() && events.isEmpty()) Text("No live activity has been reported. Past tasks are under History.", color = OrionUiPalette.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                    live.reversed().forEach { step -> StepRow(step, now, openStep == step.id) { openStep = if (openStep == step.id) null else step.id } }
                    if (events.isNotEmpty()) {
                        Text("CHAT TASK STREAM", color = OrionUiPalette.secondaryText, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        events.sortedByDescending { it.timestampMs }.forEach { event ->
                            val selected = openEvent == event.eventId
                            val tone = when (event.status) {
                                OrionActivityStatus.ACTIVE -> OrionUiPalette.accent
                                OrionActivityStatus.WAITING -> OrionUiPalette.warning
                                OrionActivityStatus.FAILED -> OrionUiPalette.danger
                                OrionActivityStatus.SUCCEEDED -> Color(0xFFB8CDBD)
                                else -> OrionUiPalette.muted
                            }
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(if (selected) OrionUiPalette.raised else Color.Transparent)
                                .clickable { openEvent = if (selected) null else event.eventId }
                                .padding(vertical = 8.dp, horizontal = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(OrionActivityPresentation.title(event), color = OrionUiPalette.text, fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(OrionActivityPresentation.summary(event), color = OrionUiPalette.muted, fontSize = 9.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(if (selected) "LESS" else "DETAILS", color = OrionUiPalette.secondaryText, fontSize = 8.sp)
                                }
                                if (selected) {
                                    event.evidence.takeLast(8).forEach { line ->
                                        Text(line, color = OrionUiPalette.muted, fontSize = 9.sp, modifier = Modifier.padding(start = 15.dp, top = 3.dp))
                                    }
                                    event.typedEvidence.forEach { evidence ->
                                        val label = when (evidence) {
                                            is OrionActivityEvidence.Browser -> "Open " + evidence.title.ifBlank { "page" }
                                            is OrionActivityEvidence.File -> "Open " + evidence.displayPath.substringAfterLast('/')
                                            is OrionActivityEvidence.Terminal -> "Open Terminal"
                                            is OrionActivityEvidence.CodeDiff -> "Open " + evidence.fileLabel.substringAfterLast('/')
                                            is OrionActivityEvidence.AgentHandoff -> "Open Agent"
                                        }
                                        Text(label, color = OrionUiPalette.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 15.dp, top = 6.dp).clickable { openActivityEvidence(context, evidence) })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(OrionUiPalette.text),
                        textStyle = TextStyle(color = OrionUiPalette.text, fontSize = 13.sp),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(OrionUiPalette.raised).padding(horizontal = 12.dp, vertical = 9.dp)) {
                                if (query.isEmpty()) Text("Search tasks, tools, files…", color = OrionUiPalette.muted, fontSize = 13.sp)
                                inner()
                            }
                        })
                    val shown = history.filter { OrionActivityHub.matches(it, query) }
                    if (shown.isEmpty()) Text("No saved tasks match.", color = OrionUiPalette.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                    shown.forEach { e ->
                        val tone = when (e.status) { "done" -> Color(0xFFB8CDBD); "failed" -> OrionUiPalette.danger; "running" -> OrionUiPalette.accent; else -> OrionUiPalette.warning }
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { openEntry = if (openEntry == e.id) null else e.id }, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${e.status.uppercase()} · ${e.steps.size} steps · " +
                                        java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.startedAt)),
                                        color = OrionUiPalette.muted, fontSize = 10.sp)
                                }
                            }
                            if (openEntry == e.id) {
                                e.steps.forEach { s -> StepRow(s, now, openStep == s.id) { openStep = if (openStep == s.id) null else s.id } }
                                if (e.status != "done") Text("Resume this task", color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(OrionUiPalette.raised)
                                        .clickable { onResume(OrionActivityHistory.resumePrompt(e)); onDismiss() }.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }
            Text("Close", color = OrionUiPalette.secondaryText, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(50)).clickable { onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
