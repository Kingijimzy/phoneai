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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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

private val ChatInk = Color(0xFF050505)
private val ChatPanel = Color(0xFF141414)
private val ChatPanelRaised = Color(0xFF202020)
private val ChatMint = Color(0xFFE5E5E5)
private val ChatCopy = Color(0xFFF7FAF9)
private val ChatMuted = Color(0xFF9B9B9B)
private val ChatHairline = Color(0xFF303030)

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
        targetValue = if (selected) Color(0xFF303030) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "mode-pill-fill"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) ChatMint else ChatMuted,
        animationSpec = tween(durationMillis = 180),
        label = "mode-pill-label"
    )
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(pillFill)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = labelColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private enum class OrionReplyType { NORMAL, RESEARCH, CODE, TASK, MCP }

private fun classifyOrionReply(item: OrionChatLine): OrionReplyType {
    if (item.kind == "note") return OrionReplyType.TASK
    val text = item.text
    val lower = text.lowercase()
    if (lower.contains("mcp tool result") || lower.contains("mcp discovery result") || lower.contains("mcp discovery failed")) {
        return OrionReplyType.MCP
    }
    if (Regex("(?s)```(?:kotlin|java|python|py|javascript|js|typescript|ts|bash|sh|json|xml|html|css|sql|rust|go)?\\s*\\n").containsMatchIn(text) ||
        (text.count { it == '\u0060' } >= 2 && (lower.contains("fun main") || lower.contains("def ") || lower.contains("import ") || lower.contains("class ")))) {
        return OrionReplyType.CODE
    }
    if (lower.contains("research summary") || lower.contains("sources:") || lower.contains("source list") ||
        (Regex("https?://").findAll(text).count() >= 2) ||
        (lower.contains("findings") && lower.contains("source"))) return OrionReplyType.RESEARCH
    if (Regex("(?m)^\\s*\\|?\\s*[-:]{3,}\\s*\\|\\s*[-:]{3,}.*$").containsMatchIn(text)) return OrionReplyType.RESEARCH
    if (lower.contains("task status") || lower.contains("execution result:") ||
        lower.contains("steps completed") || lower.contains("verification:")) return OrionReplyType.TASK
    return OrionReplyType.NORMAL
}

@Composable
internal fun ChatBubble(item: OrionChatLine, onOpenBrowser: (String?) -> Unit, onRetry: (() -> Unit)? = null, onEdit: ((String) -> Unit)? = null) {
    val mine = item.kind == "me"
    val note = item.kind == "note"
    val replyType = if (mine) OrionReplyType.NORMAL else classifyOrionReply(item)
    val clipboard = LocalClipboardManager.current
    var actionsVisible by remember(item.id) { mutableStateOf(false) }
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
                            .background(Brush.linearGradient(listOf(Color(0xFF454545), Color(0xFF1A1A1A))))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(13.dp)) }
                    Text("Orion", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("AI assistant", color = Color(0xFF9B9B9B), fontSize = 10.sp)
                }
            }
            Column(
                Modifier.fillMaxWidth(if (note) 1f else if (mine) .86f else .98f)
                    .then(
                        if (mine || note) Modifier.clip(RoundedCornerShape(17.dp))
                            .background(if (note) Color(0xFF181818) else Color(0xFF202020))
                            .border(1.dp, if (mine) Color(0xFF252525) else ChatHairline, RoundedCornerShape(17.dp))
                        else if (replyType != OrionReplyType.NORMAL) Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFF151515)
                                OrionReplyType.CODE -> Color(0xFF101010)
                                OrionReplyType.TASK -> Color(0xFF171717)
                                OrionReplyType.MCP -> Color(0xFF151515)
                                else -> Color.Transparent
                            })
                            .border(1.dp, when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFF383838)
                                OrionReplyType.CODE -> Color(0xFF383838)
                                OrionReplyType.TASK -> Color(0xFF454545)
                                OrionReplyType.MCP -> Color(0xFF454545)
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            when (replyType) {
                                OrionReplyType.RESEARCH -> "RESEARCH"
                                OrionReplyType.CODE -> "CODE OUTPUT"
                                OrionReplyType.TASK -> "TASK UPDATE"
                                OrionReplyType.MCP -> "MCP TOOL RESULT"
                                OrionReplyType.NORMAL -> "ACTIVITY"
                            },
                            color = when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFFD2D2D2)
                                OrionReplyType.CODE -> Color(0xFFE2E2E2)
                                OrionReplyType.TASK -> Color(0xFFD0D0D0)
                                OrionReplyType.MCP -> Color(0xFFCFD3D8)
                                OrionReplyType.NORMAL -> ChatMint
                            },
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("ORION", color = ChatMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                }
                when {
                    note && item.text.startsWith("[SHELL:") -> ShellActivityCard(item.text)
                    note && item.text.startsWith("[BROWSER:") -> BrowserActivityCard(item.text) { url -> onOpenBrowser(url) }
                    note && item.text.startsWith("[TASK:") -> TaskActivityCard(item.text)
                    note && (item.text.startsWith("[TOOL:") || item.text.startsWith("[TOOL_RESULT:") ||
                        item.text.startsWith("[APPROVAL:") || item.text.startsWith("[EVENT:")) -> ToolActivityCard(item.text)
                    else -> {
                        if (!mine && !note) {
                            val formatted = formatOrionMarkdown(item.text)
                            ClickableText(
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
                                modifier = Modifier.clickable(enabled = !note) { actionsVisible = !actionsVisible }
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
                        Text("Copy", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(item.text))
                            actionsVisible = false
                        }.padding(vertical = 4.dp))
                        if (mine && onEdit != null) {
                            Text("Edit & resend", color = ChatCopy, fontSize = 11.sp, modifier = Modifier.clickable {
                                actionsVisible = false
                                onEdit(item.text)
                            }.padding(vertical = 4.dp))
                        }
                        if (!mine && onRetry != null) {
                            Text("Retry response", color = ChatCopy, fontSize = 11.sp, modifier = Modifier.clickable {
                                actionsVisible = false
                                onRetry()
                            }.padding(vertical = 4.dp))
                        }
                        Text("Hide", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable {
                            actionsVisible = false
                        }.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellActivityCard(raw: String) {
    val failed = raw.startsWith("[SHELL:FAILED]")
    val command = raw.lineSequence().drop(1).firstOrNull().orEmpty().removePrefix("$ ")
    val output = raw.substringAfter("\n", "").substringAfter("\n", "").trim()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0B0B)).border(1.dp, Color(0xFF353535), RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color(0xFFD0D0D0), modifier = Modifier.size(16.dp))
            Text("ORION SHELL", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Spacer(Modifier.weight(1f))
            Text(if (failed) "FAILED" else "OUTPUT", color = if (failed) Color(0xFFE7A18F) else Color(0xFFBFC5CC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text("\$ $command", color = Color(0xFFE8E8E8), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        if (output.isNotBlank()) Text(output.take(1600), color = Color(0xFFBFC5CC), fontSize = 11.sp, lineHeight = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
private fun BrowserActivityCard(raw: String, onOpenBrowser: (String?) -> Unit) {
    val failed = raw.startsWith("[BROWSER:FAILED]")
    val url = Regex("(?m)^URL: (https?://\\S+)").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val result = raw.substringAfter("RESULT:", "").trim().take(700)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111111)).border(1.dp, Color(0xFF343434), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color(0xFFBFC5CC), modifier = Modifier.size(16.dp))
            Text("ORION BROWSER", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Spacer(Modifier.weight(1f))
            Text(if (failed) "FAILED" else "PAGE ACTIVITY", color = if (failed) Color(0xFFE7A18F) else Color(0xFFBFC5CC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (url.startsWith("https://") || url.startsWith("http://")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = ChatMuted, modifier = Modifier.size(13.dp))
                Text(url, color = ChatMuted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("OPEN", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF292929)).clickable { onOpenBrowser(url) }.padding(horizontal = 9.dp, vertical = 6.dp))
            }
            Text(
                "Page activity is summarised here to keep the conversation light. Open the page for interactive controls.",
                color = Color(0xFF858585), fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        if (result.isNotBlank()) Text(result, color = ChatMuted, fontSize = 11.sp, lineHeight = 16.sp)
        Text("Interactive pages and sign-in stay in the Browser screen.", color = Color(0xFF858585), fontSize = 9.sp, lineHeight = 13.sp)
    }
}

@Composable
@Composable
private fun ToolActivityCard(raw: String) {
    val marker = raw.lineSequence().firstOrNull().orEmpty()
    val detail = raw.substringAfter("\n", "").trim().take(900)
    val approval = marker.startsWith("[APPROVAL:")
    val result = marker.startsWith("[TOOL_RESULT:")
    val failed = marker.contains("ERROR") || marker.contains("FAILED") || marker.contains("DENIED")
    val waiting = marker.contains("WAITING") || marker.contains("PENDING")
    val status = marker.substringAfter(":", "").substringBefore("]").replace("_", " ").ifBlank { "UPDATE" }
    val title = when {
        approval -> "APPROVAL"
        result -> "TOOL RESULT"
        marker.startsWith("[TOOL:") -> "TOOL ACTIVITY"
        else -> "ORION EVENT"
    }
    val accent = when {
        failed -> Color(0xFFE7A18F)
        waiting -> Color(0xFFE5D0A5)
        else -> Color(0xFFCFD3D8)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF121212))
            .border(1.dp, Color(0xFF343434), RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(
                if (approval) Icons.Rounded.TaskAlt else Icons.Rounded.Tune,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp)
            )
            Text(title, color = Color(0xFFE8E8E8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            Spacer(Modifier.weight(1f))
            Text(status, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (detail.isNotBlank()) {
            Text(detail, color = Color(0xFFBFC5CC), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

private fun TaskActivityCard(raw: String) {
    val state = Regex("^\\[TASK:([A-Z_]+)]").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val failed = state == "FAILED"
    val running = state == "RUNNING" || state == "EXECUTE"
    val blocked = state == "BLOCKED" || state == "APPROVAL" || state == "DENIED"
    val cancelled = state == "CANCELLED"
    val recovering = state == "RECOVER"
    val detail = raw.substringAfter("\n", raw).trim()
    val label = when {
        failed -> "STEP FAILED"
        running -> "STEP RUNNING"
        blocked -> "WAITING · NEEDS ATTENTION"
        cancelled -> "TASK CANCELLED"
        recovering -> "RECOVERING · REPLANNING"
        state == "APPROVAL" -> "WAITING FOR YOUR APPROVAL"
        state == "APPROVED" -> "APPROVED"
        state == "DENIED" -> "NOT APPROVED"
        state == "PLAN" -> "PLANNING"
        state == "CHECK_CAPABILITIES" -> "CHECKING CAPABILITIES"
        state == "AUTHORIZE" -> "CHECKING APPROVALS"
        state == "VERIFY" -> "VERIFYING OUTCOME"
        state == "COMPLETE" -> "TASK COMPLETE"
        state == "RECORD" -> "RECORDING OUTCOME"
        state == "RESULT" -> "STEP RESULT"
        else -> "TASK UPDATE"
    }
    val accent = if (failed || blocked) Color(0xFFE7A18F) else Color(0xFFBFC5CC)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF171717)).border(1.dp, Color(0xFF343434), RoundedCornerShape(10.dp))
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
            Text(detail.take(300), color = ChatMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
internal fun ThinkingIndicator() {
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
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF444444), Color(0xFF181818))))
                .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(13.dp)) }
        Text("Orion is thinking", color = ChatMuted, fontSize = 12.sp)
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
        Modifier.size(43.dp).clip(CircleShape)
            .background(if (secondary) Color(0xFF242424) else ChatMint)
            .border(1.dp, if (secondary) ChatHairline else Color(0xFFE5E5E5), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = if (secondary) ChatMint else ChatInk, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun DockAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0xFF303030) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = if (selected) ChatMint else ChatMuted, modifier = Modifier.size(19.dp))
        Text(title, color = if (selected) ChatMint else ChatMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}
