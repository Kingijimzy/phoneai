package com.phoneai.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lightweight Markdown presentation for model replies; copy still uses the original source. */
private val ORION_HEADING_PATTERN = Regex("""^\s{0,3}(#{1,3})\s+(.+)$""")
private val ORION_BULLET_PATTERN = Regex("""^\s*(?:[-*+]\s+|\d+[.)]\s+)(.+)$""")
private val ORION_INLINE_TOKEN_PATTERN = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")

private fun formatOrionMarkdown(source: String): AnnotatedString {
    val out = AnnotatedString.Builder()
    source.replace("\r\n", "\n").split("\n").forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        if (index > 0) out.append("\n")
        val heading = ORION_HEADING_PATTERN.find(line)
        val bullet = ORION_BULLET_PATTERN.find(line)
        val content = heading?.groupValues?.get(2) ?: bullet?.let { "•  " + it.groupValues[1] } ?: line
        val headingSize = heading?.let {
            when (it.groupValues[1].length) { 1 -> 18.sp; 2 -> 16.sp; else -> 15.sp }
        }

        fun appendPlain(value: String) {
            if (value.isEmpty()) return
            val start = out.length
            out.append(value)
            if (headingSize != null) {
                out.addStyle(
                    androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize),
                    start, out.length
                )
            }
        }

        var cursor = 0
        ORION_INLINE_TOKEN_PATTERN.findAll(content).forEach { match ->
            appendPlain(content.substring(cursor, match.range.first))
            val isBold = match.value.startsWith("**")
            val inner = match.groupValues[if (isBold) 1 else 2]
            val start = out.length
            out.append(inner)
            out.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (isBold || headingSize != null) FontWeight.Bold else FontWeight.Medium,
                    fontSize = headingSize ?: 14.sp,
                    background = if (isBold) Color.Transparent else Color(0xFF242424)
                ),
                start, out.length
            )
            cursor = match.range.last + 1
        }
        appendPlain(content.substring(cursor))
    }
    return out.toAnnotatedString()
}
private val ChatInk = Color(0xFF050505)
private val ChatPanel = Color(0xFF141414)
private val ChatPanelRaised = Color(0xFF202020)
private val ChatMint = Color(0xFFE5E5E5)
private val ChatCopy = Color(0xFFF7FAF9)
private val ChatMuted = Color(0xFF9B9B9B)
private val ChatHairline = Color(0xFF303030)

data class OrionChatLine(
    val id: Long,
    val kind: String,
    val text: String,
    val imageUri: Uri? = null
)

@Composable
fun OrionComposeChatScreen(
    lines: List<OrionChatLine>,
    status: String,
    commandMode: Boolean,
    selectedImage: Uri?,
    providerLabel: String,
    modelLabel: String,
    providerReady: Boolean,
    onProviderClick: () -> Unit,
    onModelClick: () -> Unit,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onVoice: () -> Unit,
    onBrowser: () -> Unit,
    onTasks: () -> Unit,
    onStop: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val statusDotColor by animateColorAsState(
        targetValue = when {
            status.contains("listen", true) || status.contains("speak", true) -> ChatMint
            status.contains("think", true) || status.contains("work", true) -> Color(0xFFD0D0D0)
            else -> Color(0xFF777777)
        },
        animationSpec = tween(durationMillis = 260),
        label = "chat-status-dot"
    )
    val modeChipFill by animateColorAsState(
        targetValue = if (commandMode) Color(0xFF303030) else Color(0xFF121212),
        animationSpec = tween(durationMillis = 220),
        label = "chat-mode-fill"
    )
    val modeChipAccent by animateColorAsState(
        targetValue = if (commandMode) ChatMint else ChatCopy,
        animationSpec = tween(durationMillis = 220),
        label = "chat-mode-accent"
    )
    LaunchedEffect(lines.size, status) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    val busy = status.contains("think", true) || status.contains("work", true) || status.contains("act", true)
    Column(Modifier.fillMaxSize().background(ChatInk).imePadding()) {
        // Editorial header: quiet branding, no oversized pill around every element.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 14.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF121212))
                    .border(1.dp, ChatHairline, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowBack, null, tint = ChatCopy, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Orion", color = ChatCopy, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(statusDotColor))
                    Text(status.replaceFirstChar { it.uppercase() }.take(24), color = ChatMuted, fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier.padding(end = 7.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onNewChat).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Rounded.Add, null, tint = ChatCopy, modifier = Modifier.size(15.dp))
                Text("New", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onHistory).padding(horizontal = 7.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("History", color = ChatMuted, fontSize = 11.sp)
            }
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(modeChipFill)
                    .border(1.dp, ChatHairline, RoundedCornerShape(9.dp)).clickable { onModeChange(!commandMode) }
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (commandMode) Icons.Rounded.Tune else Icons.Rounded.Waves, null,
                        tint = modeChipAccent, modifier = Modifier.size(15.dp))
                    Text(if (commandMode) "Command" else "Talk", color = modeChipAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ChatHairline.copy(alpha = .55f)))

        if (lines.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ORION", color = ChatCopy, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("PERSONAL AI  /  READY WHEN YOU ARE", color = ChatMuted,
                            fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        listOf(7, 12, 17, 11, 6).forEachIndexed { index, barHeight ->
                            Box(
                                Modifier.width(3.dp).height(barHeight.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (index == 2) ChatCopy else ChatHairline)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(if (commandMode) "What can I take\noff your hands?" else "What’s on your\nmind today?",
                    color = ChatCopy, fontSize = 31.sp, lineHeight = 35.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = (-.9).sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (commandMode) "Tell Orion what you want done. It will keep you informed and ask before sensitive actions."
                    else "Start with a question, an idea, or something you want to understand. Just talk naturally.",
                    color = ChatMuted, fontSize = 13.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(24.dp))
                Text("TRY ASKING", color = Color(0xFF9B9B9B), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                listOf(
                    Triple("Help me think", "Work through an idea together", "Help me think through an idea"),
                    Triple("Explain it simply", "Make a difficult topic clear", "Explain something simply"),
                    Triple("Plan my next step", "Turn a goal into a practical plan", "Help me plan my next step")
                ).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF121212)).border(1.dp, ChatHairline, RoundedCornerShape(16.dp))
                            .clickable { onSend(item.third) }.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.first, color = ChatCopy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.second, color = ChatMuted, fontSize = 11.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = ChatMint.copy(alpha = .8f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 20.dp)
            ) {
                itemsIndexed(lines, key = { _, item -> item.id }) { _, item -> ChatBubble(item) }
                if (busy) item(key = "orion-thinking-indicator") { ThinkingIndicator() }
            }
        }

        AnimatedVisibility(selectedImage != null, enter = fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(180)) { it / 3 }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp)).background(ChatPanelRaised)
                    .border(1.dp, ChatHairline, RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedImage?.let { ImageThumbnail(it, Modifier.size(width = 48.dp, height = 48.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Image attached", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Ready to analyse", color = ChatMuted, fontSize = 11.sp)
                }
                Text("Remove", color = ChatMint, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onRemoveImage).padding(7.dp))
            }
        }

        // Composer is the primary surface; provider and model switching stay beside the input.
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF171717))
                        .border(1.dp, if (providerReady) Color(0xFF414141) else Color(0xFF8A5D42), RoundedCornerShape(10.dp))
                        .clickable(onClick = onProviderClick)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (providerReady) Color(0xFFDADADA) else Color(0xFFE2A16F)))
                    Column(Modifier.weight(1f)) {
                        Text("PROVIDER", color = ChatMuted, fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold)
                        Text(providerLabel, color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = ChatMuted, modifier = Modifier.size(14.dp))
                }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF171717))
                        .border(1.dp, ChatHairline, RoundedCornerShape(10.dp))
                        .clickable(onClick = onModelClick)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Tune, null, tint = ChatMuted, modifier = Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MODEL", color = ChatMuted, fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold)
                        Text(modelLabel, color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = ChatMuted, modifier = Modifier.size(14.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF222222), Color(0xFF101010))))
                    .border(1.dp, Color(0xFF444444), RoundedCornerShape(19.dp))
                    .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    if (draft.isEmpty()) Text(if (commandMode) "Describe the task…" else "Message Orion…", color = ChatMuted, fontSize = 14.sp)
                    BasicTextField(
                        value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ChatCopy, fontSize = 14.sp, lineHeight = 21.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() || selectedImage != null) { onSend(draft.trim()); draft = "" }
                        }), maxLines = 5
                    )
                }
                ComposerButton(Icons.Rounded.AddPhotoAlternate, "Attach image", onPickImage, secondary = true)
                Spacer(Modifier.width(5.dp))
                ComposerButton(Icons.Rounded.Send, "Send message", {
                    if (draft.isNotBlank() || selectedImage != null) { onSend(draft.trim()); draft = "" }
                }, secondary = false)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, start = 5.dp, end = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live voice", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onVoice))
                    Text("Browser", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onBrowser))
                    Text("Tasks", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onTasks))
                }
                Text("STOP", color = if (status.contains("think", true) || status.contains("work", true)) Color(0xFFE9B984) else Color(0xFF9B9B9B),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onStop).padding(4.dp))
            }
        }
    }
}


@Composable
private fun ImageThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = when {
                            bounds.outWidth > 2400 || bounds.outHeight > 2400 -> 4
                            bounds.outWidth > 1200 || bounds.outHeight > 1200 -> 2
                            else -> 1
                        }
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

private enum class OrionReplyType { NORMAL, RESEARCH, CODE, TASK }

private fun classifyOrionReply(item: OrionChatLine): OrionReplyType {
    if (item.kind == "note") return OrionReplyType.TASK
    val text = item.text
    val lower = text.lowercase()
    if (Regex("(?s)```(?:kotlin|java|python|py|javascript|js|typescript|ts|bash|sh|json|xml|html|css|sql|rust|go)?\\s*\\n").containsMatchIn(text) ||
        (text.count { it == '\u0060' } >= 2 && (lower.contains("fun main") || lower.contains("def ") || lower.contains("import ") || lower.contains("class "))) {
        return OrionReplyType.CODE
    }
    if (lower.contains("research summary") || lower.contains("sources:") || lower.contains("source list") ||
        (Regex("https?://").findAll(text).count() >= 2) ||
        (lower.contains("findings") && lower.contains("source"))) return OrionReplyType.RESEARCH
    if (lower.contains("task status") || lower.contains("execution result:") ||
        lower.contains("steps completed") || lower.contains("verification:")) return OrionReplyType.TASK
    return OrionReplyType.NORMAL
}

@Composable
private fun ChatBubble(item: OrionChatLine) {
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
            Modifier.fillMaxWidth(),
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
                                OrionReplyType.RESEARCH -> Color(0xFF111820)
                                OrionReplyType.CODE -> Color(0xFF101010)
                                OrionReplyType.TASK -> Color(0xFF171717)
                                else -> Color.Transparent
                            })
                            .border(1.dp, when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFF354553)
                                OrionReplyType.CODE -> Color(0xFF383838)
                                OrionReplyType.TASK -> Color(0xFF454545)
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
                                OrionReplyType.NORMAL -> "ACTIVITY"
                            },
                            color = when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFFB6D5E8)
                                OrionReplyType.CODE -> Color(0xFFE2E2E2)
                                OrionReplyType.TASK -> Color(0xFFD0D0D0)
                                OrionReplyType.NORMAL -> ChatMint
                            },
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("ORION", color = ChatMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                }
                Text(
                    text = if (!mine && !note) formatOrionMarkdown(item.text) else AnnotatedString(item.text),
                    color = if (note) ChatMuted else ChatCopy,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.clickable(enabled = !note) { actionsVisible = !actionsVisible }
                )
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
private fun ThinkingIndicator() {
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
private fun ComposerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, secondary: Boolean) {
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
