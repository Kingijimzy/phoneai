package com.phoneai.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ChatInk = OrionUiPalette.background
private val ChatPanel = OrionUiPalette.panel
private val ChatPanelRaised = OrionUiPalette.raised
private val ChatMint = OrionUiPalette.accent
private val ChatCopy = OrionUiPalette.text
private val ChatMuted = OrionUiPalette.muted
private val ChatHairline = OrionUiPalette.border

@Composable
internal fun ImageThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = orionImageSampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeStream(input, null, options)
                }
            } catch (_: Exception) { null }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(ChatPanelRaised)
            .border(1.dp, ChatHairline, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Selected image preview",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Image preview unavailable",
                tint = ChatMuted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pillFill by animateColorAsState(
        targetValue = if (selected) OrionUiPalette.border else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "mode-pill-fill"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) ChatMint else ChatMuted,
        animationSpec = tween(durationMillis = 180),
        label = "mode-pill-label"
    )
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(pillFill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label mode"; role = Role.Button }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = labelColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private enum class OrionReplyType { NORMAL, RESEARCH, CODE, TASK, MCP, DIAGRAM }

private fun classifyOrionReply(item: OrionChatLine): OrionReplyType {
    if (item.kind == "note") return OrionReplyType.TASK
    // New messages carry explicit block types. Legacy text is adapted at restoration time;
    // ordinary prose is never promoted to a tool/task card because it contains a keyword.
    val blocks = item.content.blocks
    if (blocks.any { it is OrionMessageBlock.Code }) return OrionReplyType.CODE
    if (blocks.any { it is OrionMessageBlock.Terminal || it is OrionMessageBlock.Approval ||
            it is OrionMessageBlock.TaskReceipt || it is OrionMessageBlock.GeneratedFile }) return OrionReplyType.TASK
    if (blocks.any { it is OrionMessageBlock.ToolResult }) return OrionReplyType.MCP
    if (blocks.any { it is OrionMessageBlock.Research }) return OrionReplyType.RESEARCH
    if (parseOrionDiagram(item.text) != null) return OrionReplyType.DIAGRAM
    return OrionReplyType.NORMAL
}

@Composable
internal fun ChatBubble(item: OrionChatLine, onOpenBrowser: (String?) -> Unit, onRetry: (() -> Unit)? = null, onEdit: ((String) -> Unit)? = null) {
    val mine = item.kind == "me"
    val note = item.kind == "note"
    val replyType = if (mine) OrionReplyType.NORMAL else classifyOrionReply(item)
    val clipboard = LocalClipboardManager.current
    var actionsVisible by remember(item.id) { mutableStateOf(false) }
    var openBlock by remember(item.id) { mutableStateOf(false) }
    val collapsible = !mine && (replyType != OrionReplyType.NORMAL || (note && !item.text.startsWith("[")))
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(260)) +
            slideInVertically(animationSpec = tween(260)) { it / 10 }
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180)),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            if (!mine && !note) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 7.dp)
                ) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(OrionUiPalette.borderStrong, OrionUiPalette.panel)))
                            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(13.dp)) }
                    Text("Orion", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("AI assistant", color = OrionUiPalette.muted, fontSize = 10.sp)
                }
            }
            Column(
                (if (mine) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(if (note) 1f else .98f))
                    .then(
                        if (mine || note) Modifier.clip(RoundedCornerShape(17.dp))
                            .background(if (note) OrionUiPalette.panel else OrionUiPalette.raised)
                            .border(1.dp, if (mine) Color(0x00000000) else Color(0x40545458), RoundedCornerShape(17.dp))
                        else if (replyType != OrionReplyType.NORMAL) Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(when (replyType) {
                                OrionReplyType.RESEARCH -> OrionUiPalette.panel
                                OrionReplyType.CODE -> OrionUiPalette.panel
                                OrionReplyType.TASK -> OrionUiPalette.panel
                                OrionReplyType.MCP -> OrionUiPalette.panel
                                OrionReplyType.DIAGRAM -> OrionUiPalette.panel
                                else -> Color.Transparent
                            })
                            .border(1.dp, when (replyType) {
                                OrionReplyType.RESEARCH -> OrionUiPalette.borderStrong
                                OrionReplyType.CODE -> OrionUiPalette.borderStrong
                                OrionReplyType.TASK -> OrionUiPalette.borderStrong
                                OrionReplyType.MCP -> OrionUiPalette.borderStrong
                                OrionReplyType.DIAGRAM -> OrionUiPalette.borderStrong
                                else -> Color.Transparent
                            }, RoundedCornerShape(16.dp))
                        else Modifier
                    )
                    .padding(
                        horizontal = if (mine || note || replyType != OrionReplyType.NORMAL) 13.dp else 0.dp,
                        vertical = if (mine || note || replyType != OrionReplyType.NORMAL) 12.dp else 0.dp
                    )
            ) {
                if (note || replyType != OrionReplyType.NORMAL) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = collapsible) { openBlock = !openBlock }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            when (replyType) {
                                OrionReplyType.RESEARCH -> "RESEARCH"
                                OrionReplyType.CODE -> "CODE OUTPUT"
                                OrionReplyType.TASK -> "TASK UPDATE"
                                OrionReplyType.MCP -> "MCP TOOL RESULT"
                                OrionReplyType.DIAGRAM -> "VISUAL EXPLANATION"
                                OrionReplyType.NORMAL -> "ACTIVITY"
                            },
                            color = when (replyType) {
                                OrionReplyType.RESEARCH -> OrionUiPalette.secondaryText
                                OrionReplyType.CODE -> OrionUiPalette.secondaryText
                                OrionReplyType.TASK -> OrionUiPalette.secondaryText
                                OrionReplyType.MCP -> OrionUiPalette.secondaryText
                                OrionReplyType.DIAGRAM -> OrionUiPalette.secondaryText
                                OrionReplyType.NORMAL -> ChatMint
                            },
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("ORION", color = ChatMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                        if (collapsible) Icon(if (openBlock) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = ChatMuted, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(7.dp))
                }
                when {
                    note && item.text.startsWith("[SHELL:") -> ShellActivityCard(item.text)
                    note && item.text.startsWith("[IMAGE]") -> ImageResultCard(item.text)
                    note && item.text.startsWith("[BROWSER:") -> BrowserActivityCard(item.text) { url -> onOpenBrowser(url) }
                    note && item.text.startsWith("[TASK:") -> TaskActivityCard(item.text)
                    note && (item.text.startsWith("[TOOL:") || item.text.startsWith("[TOOL_RESULT:") ||
                        item.text.startsWith("[APPROVAL:") || item.text.startsWith("[EVENT:")) -> ToolActivityCard(item.text)
                    !mine && !note && replyType == OrionReplyType.DIAGRAM -> DiagramCard(item.text)
                    else -> {
                        if (!mine && !note) {
                            val formatted = formatOrionMarkdown(item.text)
                            ClickableText(
                                maxLines = if (collapsible && !openBlock) 3 else Int.MAX_VALUE,
                                text = formatted,
                                style = TextStyle(color = ChatCopy, fontSize = 14.sp, lineHeight = 22.sp),
                                onClick = { offset ->
                                    val url = formatted.getStringAnnotations("URL", offset, (offset + 1).coerceAtMost(formatted.length)).firstOrNull()?.item
                                    if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                                        onOpenBrowser(url)
                                    } else {
                                        actionsVisible = !actionsVisible
                                    }
                                }
                            )
                        } else {
                            Text(
                                text = AnnotatedString(item.text),
                                color = if (note) ChatMuted else ChatCopy,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                maxLines = if (collapsible && !openBlock) 3 else Int.MAX_VALUE,
                                modifier = Modifier.clickable { if (collapsible) openBlock = !openBlock else actionsVisible = !actionsVisible }
                            )
                        }
                    }
                }
                if (item.imageUri != null) {
                    Spacer(Modifier.height(9.dp))
                    ImageThumbnail(item.imageUri, Modifier.fillMaxWidth().height(190.dp))
                }
                AnimatedVisibility(visible = actionsVisible && !note, enter = fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 }) {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Copy", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { clipboard.setText(AnnotatedString(item.text)); actionsVisible = false }
                            .semantics { role = Role.Button; contentDescription = "Copy message" }
                            .padding(horizontal = 8.dp, vertical = 10.dp))
                        if (mine && onEdit != null) {
                            Text("Edit & resend", color = ChatCopy, fontSize = 11.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { actionsVisible = false; onEdit(item.text) }
                                .semantics { role = Role.Button; contentDescription = "Edit and resend message" }
                                .padding(horizontal = 8.dp, vertical = 10.dp))
                        }
                        if (!mine && onRetry != null) {
                            Text("Retry response", color = ChatCopy, fontSize = 11.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { actionsVisible = false; onRetry() }
                                .semantics { role = Role.Button; contentDescription = "Retry response" }
                                .padding(horizontal = 8.dp, vertical = 10.dp))
                        }
                        Text("Hide", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.heightIn(min = 44.dp).clickable { actionsVisible = false }
                            .semantics { role = Role.Button; contentDescription = "Hide message actions" }
                            .padding(horizontal = 8.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramCard(raw: String) {
    val model = remember(raw) { parseOrionDiagram(raw) }
    if (model == null) {
        Text(raw, color = ChatCopy, fontSize = 13.sp, lineHeight = 19.sp)
        return
    }
    val horizontal = model.direction == "LR" || model.direction == "RL"
    val orderedIds = remember(model) {
        val connected = model.edges.flatMap { listOf(it.from, it.to) }.distinct()
        (connected + model.nodes.map { it.id }).distinct().let {
            if (model.direction == "RL" || model.direction == "BT") it.reversed() else it
        }
    }
    val byId = remember(model) { model.nodes.associateBy { it.id } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("FLOW", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (horizontal) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                orderedIds.forEachIndexed { index, id ->
                    DiagramNodeChip(byId[id]?.label ?: id, index == 0)
                    if (index < orderedIds.lastIndex) Text("→", color = OrionUiPalette.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                orderedIds.forEachIndexed { index, id ->
                    DiagramNodeChip(byId[id]?.label ?: id, index == 0)
                    if (index < orderedIds.lastIndex) Text("↓", color = OrionUiPalette.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "${model.nodes.size} nodes · ${model.edges.size} connections",
            color = OrionUiPalette.muted, fontSize = 9.sp
        )
    }
}

@Composable
private fun DiagramNodeChip(label: String, first: Boolean) {
    Box(
        Modifier.widthIn(min = 120.dp, max = 250.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (first) OrionUiPalette.raised else OrionUiPalette.background)
            .border(1.dp, if (first) OrionUiPalette.accent.copy(alpha = .75f) else OrionUiPalette.borderStrong, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ShellActivityCard(raw: String) {
    val transcript = remember(raw) { OrionShellTranscriptParser.parse(raw) }
    val failed = transcript.failed
    val running = transcript.running
    val command = transcript.command
    val code = transcript.code
    val output = transcript.output
    val title = when {
        command.startsWith("python", true) -> "ORION PYTHON"
        command.startsWith("write ", true) -> "ORION CODE"
        command.startsWith("cat ", true) -> "ORION FILE"
        else -> "ORION TERMINAL"
    }
    val stateColor = when {
        failed -> OrionUiPalette.danger
        running -> OrionUiPalette.warning
        transcript.phase in setOf("COMPLETE", "DONE", "SUCCESS") -> OrionUiPalette.success
        else -> OrionUiPalette.secondaryText
    }
    val stateLabel = when {
        failed -> "FAILED"
        running -> "RUNNING"
        transcript.phase in setOf("COMPLETE", "DONE", "SUCCESS") -> "DONE"
        else -> transcript.phase
    }
    val clipboard = LocalClipboardManager.current
    var expanded by remember(raw) { mutableStateOf(false) }
    var open by remember(raw) { mutableStateOf(failed || running) }
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val outputPreview = if (expanded) output.takeLast(8000) else output.takeLast(900)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).animateContentSize(animationSpec = tween(220))
            .background(OrionUiPalette.panel)
            .border(1.dp, if (failed) OrionUiPalette.danger else OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(stateColor))
            Text(title, color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Spacer(Modifier.weight(1f))
            Text(stateLabel, color = stateColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = ChatMuted, modifier = Modifier.size(16.dp))
        }
        if (!open) Text(command.take(100), color = ChatMuted, fontSize = 11.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (open) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = stateColor)) { append("\$ ") }
                append(command)
            }, color = OrionUiPalette.text, fontSize = 12.sp, fontFamily = mono)
            if (code.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SOURCE", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${code.lineSequence().count()} LINES", color = OrionUiPalette.muted, fontSize = 8.sp)
                }
                val lines = code.lines()
                val shown = if (expanded) lines.take(240) else lines.take(10)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(OrionUiPalette.background).padding(8.dp)
                ) {
                    shown.forEachIndexed { n, line ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${n + 1}".padStart(3), color = OrionUiPalette.muted, fontSize = 10.sp, fontFamily = mono)
                            Spacer(Modifier.width(8.dp))
                            Text(highlightCode(line.take(240)), color = OrionUiPalette.secondaryText,
                                fontSize = 11.sp, lineHeight = 15.sp, fontFamily = mono)
                        }
                    }
                    if (lines.size > 10) Text(
                        if (expanded) "SHOW LESS" else "+${lines.size - 10} more lines",
                        color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(top = 6.dp)
                    )
                }
            }
            if (output.isNotBlank()) {
                Text("OUTPUT", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                Text(outputPreview, color = if (failed) OrionUiPalette.danger else OrionUiPalette.secondaryText,
                    fontSize = 11.sp, lineHeight = 16.sp, fontFamily = mono)
                if (output.length > 900) {
                    Text(if (expanded) "SHOW LESS OUTPUT" else "SHOW FULL OUTPUT",
                        color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp))
                }
            }
            Text("COPY TRANSCRIPT", color = ChatMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val complete = buildString {
                        if (command.isNotBlank()) append("\$ ").append(command).append("\n")
                        if (code.isNotBlank()) append("\n").append(code).append("\n")
                        if (output.isNotBlank()) append("\n").append(output)
                    }
                    clipboard.setText(AnnotatedString(complete))
                }.padding(vertical = 2.dp))
        }
    }
}
@Composable
private fun BrowserActivityCard(raw: String, onOpenBrowser: (String?) -> Unit) {
    val failed = raw.startsWith("[BROWSER:FAILED]")
    val url = Regex("(?m)^URL: (https?://\\S+)").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val result = raw.substringAfter("RESULT:", "").trim().take(700)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = OrionUiPalette.secondaryText, modifier = Modifier.size(16.dp))
            Text("ORION BROWSER", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Spacer(Modifier.weight(1f))
            Text(if (failed) "FAILED" else "PAGE ACTIVITY", color = if (failed) OrionUiPalette.danger else OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (url.startsWith("https://") || url.startsWith("http://")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = ChatMuted, modifier = Modifier.size(13.dp))
                Text(url, color = ChatMuted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("OPEN", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(OrionUiPalette.raised).clickable { onOpenBrowser(url) }.padding(horizontal = 9.dp, vertical = 6.dp))
            }
            Text(
                "Page activity is summarised here to keep the conversation light. Open the page for interactive controls.",
                color = OrionUiPalette.muted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        if (result.isNotBlank()) Text(result, color = ChatMuted, fontSize = 11.sp, lineHeight = 16.sp)
        Text("Interactive pages open in the in-chat browser popup. Use its Close control to return here.", color = OrionUiPalette.muted, fontSize = 9.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun ToolActivityCard(raw: String) {
    val marker = raw.lineSequence().firstOrNull().orEmpty()
    val detail = raw.substringAfter("\n", "").trim().take(2400)
    val clipboard = LocalClipboardManager.current
    var expanded by remember(raw) { mutableStateOf(false) }
    val warning = marker.startsWith("[APPROVAL:")
    val result = marker.startsWith("[TOOL_RESULT:")
    val failed = marker.contains("ERROR") || marker.contains("FAILED") || marker.contains("DENIED")
    val waiting = marker.contains("WAITING") || marker.contains("PENDING")
    val status = marker.substringAfter(":", "").substringBefore("]").replace("_", " ").ifBlank { "UPDATE" }
    val active = marker.contains("ACTIVE") || marker.contains("RUNNING") || marker.contains("STARTED") || marker.contains("IN_PROGRESS") || marker.contains("WORKING")
    val performance = when {
        detail.contains("browser", true) || detail.contains("http", true) -> "Browsing"
        detail.contains("python", true) || detail.contains("build", true) || detail.contains("code", true) -> "Building"
        detail.contains("screen", true) || detail.contains("click", true) || detail.contains("tap", true) -> "Using phone"
        detail.contains("voice", true) || detail.contains("speech", true) -> "Speaking"
        detail.contains("search", true) || detail.contains("research", true) -> "Researching"
        detail.contains("mcp", true) || detail.contains("connect", true) -> "Connecting"
        else -> "Working"
    }
    val companionStatus = when {
        performance == "Speaking" -> "Speaking"
        performance == "Listening" -> "Listening"
        active -> performance
        else -> "Ready"
    }
    val companionMood = when {
        failed -> CompanionMood.Mood.ALERT
        warning || waiting -> CompanionMood.Mood.WORRIED
        result -> CompanionMood.Mood.HAPPY
        else -> CompanionMood.Mood.CALM
    }
    val title = when {
        warning -> "RISK WARNING"
        result -> "TOOL RESULT"
        marker.startsWith("[TOOL:") -> "TOOL ACTIVITY"
        else -> "ORION EVENT"
    }
    val accent = when {
        failed -> OrionUiPalette.danger
        waiting -> OrionUiPalette.warning
        else -> OrionUiPalette.secondaryText
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AndroidView(
                modifier = Modifier.size(38.dp),
                factory = { context -> CompanionView(context).apply { character = Prefs.avatar(context) } },
                update = { companion ->
                    companion.activity = companionActivityForChatStatus(companionStatus)
                    companion.setMood(companionMood)
                    val eventKey = marker + "|" + detail.take(180)
                    if (companion.tag != eventKey) {
                        companion.tag = eventKey
                        when {
                            failed -> companion.react(CompanionLife.Reaction.FLINCH)
                            result -> companion.react(CompanionLife.Reaction.CELEBRATE)
                        }
                    }
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = OrionUiPalette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                Text(performance, color = OrionUiPalette.muted, fontSize = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(status, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = OrionUiPalette.secondaryText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = if (expanded) 12 else 3,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.length > 150) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expanded) "SHOW LESS" else "SHOW DETAILS",
                        color = OrionUiPalette.secondaryText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 5.dp)
                    )
                    Text(
                        "COPY",
                        color = ChatMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(detail))
                        }.padding(vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskActivityCard(raw: String) {
    val state = Regex("^\\[TASK:([A-Z_]+)]").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val failed = state == "FAILED"
    val running = state == "RUNNING" || state == "EXECUTE"
    val blocked = state == "BLOCKED" || state == "DENIED"
    val cancelled = state == "CANCELLED"
    val recovering = state == "RECOVER"
    val detail = raw.substringAfter("\n", raw).trim()
    val label = when {
        failed -> "STEP FAILED"
        running -> "STEP RUNNING"
        blocked -> "WAITING · NEEDS ATTENTION"
        cancelled -> "TASK CANCELLED"
        recovering -> "RECOVERING · REPLANNING"
        state == "APPROVAL" -> "RISK WARNING · CONTINUING"
        state == "APPROVED" -> "CONTINUING"
        state == "DENIED" -> "ACTION FAILED"
        state == "PLANNED" -> "PLAN"
        state == "PLAN" -> "PLANNING"
        state == "CHECK_CAPABILITIES" -> "CHECKING CAPABILITIES"
        state == "AUTHORIZE" -> "CHECKING PLATFORM ACCESS"
        state == "VERIFY" -> "VERIFYING OUTCOME"
        state == "COMPLETE" -> "TASK COMPLETE"
        state == "RECORD" -> "RECORDING OUTCOME"
        state == "RESULT" -> "STEP RESULT"
        else -> "TASK UPDATE"
    }
    val accent = if (failed || blocked) OrionUiPalette.danger else OrionUiPalette.secondaryText
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            if (failed || cancelled) Icons.Rounded.Stop else if (running || recovering) Icons.Rounded.Bolt else Icons.Rounded.TaskAlt,
            contentDescription = null, tint = accent, modifier = Modifier.size(16.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = ChatCopy, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Text(detail.take(if (state == "PLANNED") 560 else 300), color = ChatMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

internal fun orionWorkingPhrase(status: String): String {
    val value = status.lowercase()
    return when {
        "recover" in value || "retry" in value -> "Recovering and trying another route"
        "fix" in value || "repair" in value -> "Fixing the issue"
        "verif" in value || "test" in value -> "Checking that the result works"
        "terminal" in value || "command" in value || "execut" in value || "run" in value -> "Running the next step"
        "search" in value || "research" in value || "browser" in value -> "Looking for relevant information"
        "code" in value || "build" in value || "develop" in value -> "Working on the implementation"
        "plan" in value || "think" in value -> "Thinking through the best approach"
        "solve" in value || "tackle" in value || "work" in value || "act" in value -> "Working through the task"
        else -> "Working through the next step"
    }
}

@Composable
fun ThinkingIndicator(status: String = "Thinking") {
    val phrase = remember(status) { orionWorkingPhrase(status) }
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "thinking-phase"
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 1.dp, top = 3.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OrionOrb(Modifier.size(44.dp), busy = true)
        Text(phrase, color = ChatMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                val distance = kotlin.math.abs(((phase * 3f + index) % 3f) - 1.5f) / 1.5f
                Box(Modifier.size(4.dp).scale(0.7f + (1f - distance) * .55f)
                    .clip(CircleShape).background(ChatMint.copy(alpha = .35f + (1f - distance) * .65f)))
            }
        }
    }
}

@Composable
internal fun ComposerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, secondary: Boolean) {
    Box(
        Modifier.pressScale().size(48.dp).clip(CircleShape)
            .background(if (secondary) OrionUiPalette.raised else ChatMint)
            .border(1.dp, if (secondary) ChatHairline else OrionUiPalette.accent, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.Crossfade(icon, animationSpec = androidx.compose.animation.core.tween(160), label = "composer-icon") { shown ->
            Icon(shown, contentDescription = description, tint = if (secondary) ChatMint else ChatInk, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun DockAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) OrionUiPalette.border else Color.Transparent)
            .clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = title }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = if (selected) ChatMint else ChatMuted, modifier = Modifier.size(19.dp))
        Text(title, color = if (selected) ChatMint else ChatMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private val codeToken = Regex("(#.*$)|(\"\"\".*?\"\"\"|\"[^\"]*\"|'[^']*')|\\b(def|class|import|from|return|if|elif|else|for|while|try|except|finally|with|as|in|not|and|or|None|True|False|lambda|yield|raise|pass|break|continue|print)\\b|\\b(\\d+(?:\\.\\d+)?)\\b")

/** Light syntax colouring so code cards read like an editor, not a black wall of text. */
private fun highlightCode(line: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in codeToken.findAll(line)) {
        append(line.substring(last, m.range.first))
        val color = when {
            m.groups[1] != null -> Color(0xFF6A737D)
            m.groups[2] != null -> Color(0xFFA5D6A7)
            m.groups[3] != null -> Color(0xFFC792EA)
            else -> Color(0xFFF2A65A)
        }
        withStyle(SpanStyle(color = color)) { append(m.value) }
        last = m.range.last + 1
    }
    append(line.substring(last))
}

/** A generated or shown image drawn inline in the conversation. */
@Composable
internal fun ImageResultCard(raw: String) {
    val lines = raw.lines()
    val path = lines.getOrNull(1).orEmpty()
    val caption = lines.getOrNull(2).orEmpty()
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = orionImageSampleSize(bounds.outWidth, bounds.outHeight) })
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, RoundedCornerShape(18.dp))) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = caption.ifBlank { "Image from Orion" }, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        } else {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Text(if (java.io.File(path).exists()) "Loading image…" else "Image file not found: ${path.substringAfterLast('/')}", color = ChatMuted, fontSize = 12.sp) }
        }
        if (caption.isNotBlank()) Text(caption, color = ChatMuted, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
    }
}


/** Renders a durable SQLite activity receipt without inferring its state from free-form text. */
@Composable
internal fun OrionPersistedActivityCard(event: OrionConversationRepository.ChatEvent) {
    val clipboard = LocalClipboardManager.current
    val failed = event.status.equals("failed", true) ||
        event.status.equals("error", true) || event.status.equals("denied", true)
    val waiting = event.status.equals("waiting", true) ||
        event.status.equals("pending", true) || event.status.equals("approval", true)
    val accent = when {
        failed -> OrionUiPalette.danger
        waiting -> OrionUiPalette.warning
        event.status.equals("completed", true) || event.status.equals("success", true) ->
            OrionUiPalette.success
        else -> OrionUiPalette.secondaryText
    }
    var expanded by remember(event.id) { mutableStateOf(false) }
    val evidence = event.evidenceJson.orEmpty()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Text(event.type.replace('_', ' ').uppercase(), color = OrionUiPalette.text,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp)
            Spacer(Modifier.weight(1f))
            Text(event.status.replace('_', ' ').uppercase(), color = accent,
                fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(event.summary, color = OrionUiPalette.secondaryText, fontSize = 12.sp, lineHeight = 17.sp)
        if (evidence.isNotBlank()) {
            Text(
                if (expanded) "HIDE STORED EVIDENCE" else "VIEW STORED EVIDENCE",
                color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 3.dp)
            )
            if (expanded) {
                Text(evidence, color = OrionUiPalette.secondaryText,
                    fontSize = 10.sp, lineHeight = 14.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Receipt " + event.id.take(8), color = OrionUiPalette.muted, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text("COPY", color = OrionUiPalette.muted, fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(event.summary + if (evidence.isNotBlank()) "\n" + evidence else ""))
                }.padding(5.dp))
        }
    }
}



@Composable
internal fun OrionChatAttachmentCard(
    attachment: OrionChatAttachmentStore.Attachment,
    onOpen: (OrionChatAttachmentStore.Attachment) -> Unit
) {
    val extension = attachment.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
    val sizeLabel = when {
        attachment.sizeBytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", attachment.sizeBytes / (1024f * 1024f))
        attachment.sizeBytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", attachment.sizeBytes / 1024f)
        else -> attachment.sizeBytes.toString() + " B"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(13.dp))
            .clickable { onOpen(attachment) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                .background(OrionUiPalette.raised)
                .border(1.dp, OrionUiPalette.border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when (extension) {
                    "PDF" -> "PDF"
                    "DOCX", "DOC" -> "DOC"
                    "XLSX", "XLS", "CSV" -> "XLS"
                    "PPTX", "PPT" -> "PPT"
                    "PNG", "JPG", "JPEG", "WEBP" -> "IMG"
                    else -> "FILE"
                },
                color = OrionUiPalette.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(attachment.name, color = OrionUiPalette.text, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(extension + " · " + sizeLabel, color = OrionUiPalette.muted, fontSize = 10.sp)
        }
        Text("OPEN", color = OrionUiPalette.secondaryText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
