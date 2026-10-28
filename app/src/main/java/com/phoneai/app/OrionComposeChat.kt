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

/** Choose power-of-two sampling so previews stay near 1200px even for very large photos. */
internal fun orionImageSampleSize(width: Int, height: Int, maxDimension: Int = 1200): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sample = 1
    while (maxOf(width / sample, height / sample) > maxDimension && sample <= (1 shl 29)) {
        sample *= 2
    }
    return sample
}

/** Lightweight Markdown presentation for model replies; copy still uses the original source. */
private val ORION_HEADING_PATTERN = Regex("""^\s{0,3}(#{1,3})\s+(.+)$""")
private val ORION_BULLET_PATTERN = Regex("""^\s*(?:[-*+]\s+|\d+[.)]\s+)(.+)$""")
private val ORION_INLINE_TOKEN_PATTERN = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)|\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*([^*\n]+)\*(?!\w)|(https?://[^\s)]+)""")

private fun formatOrionMarkdown(source: String): AnnotatedString {
    val out = AnnotatedString.Builder()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var inCode = false
    var codeFirstLine = false
    var codeStart = -1
    var hasRenderedLine = false

    lines.forEach { raw ->
        if (raw.trimStart().startsWith("```")) {
            if (!inCode) {
                if (hasRenderedLine) out.append("\n")
                inCode = true
                codeFirstLine = true
                codeStart = out.length
                hasRenderedLine = true
            } else {
                if (codeStart >= 0 && out.length > codeStart) {
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFF0F0F0),
                            background = Color(0xFF242424)
                        ),
                        codeStart, out.length
                    )
                }
                inCode = false
                codeFirstLine = false
                codeStart = -1
            }
            return@forEach
        }

        if (hasRenderedLine && (!inCode || !codeFirstLine)) out.append("\n")
        if (inCode) {
            val lineStart = out.length
            out.append(raw)
            if (raw.isNotEmpty()) {
                out.addStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFF0F0F0),
                        background = Color(0xFF242424)
                    ),
                    lineStart, out.length
                )
            }
            codeFirstLine = false
            hasRenderedLine = true
            return@forEach
        }

        val line = raw.trimEnd()
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
            val start = out.length
            when {
                match.groups[1] != null && match.groups[2] != null -> {
                    out.append(match.groupValues[1])
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = Color(0xFFD2D6DA),
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        ), start, out.length
                    )
                }
                match.groups[3] != null -> {
                    out.append(match.groupValues[3])
                    out.addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), start, out.length)
                }
                match.groups[4] != null -> {
                    out.append(match.groupValues[4])
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                            background = Color(0xFF242424)
                        ), start, out.length
                    )
                }
                match.groups[5] != null -> {
                    out.append(match.groupValues[5])
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        start, out.length
                    )
                }
                else -> {
                    out.append(match.groupValues[6].trimEnd('.', ',', ';'))
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = Color(0xFFD2D6DA),
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        ), start, out.length
                    )
                }
            }
            cursor = match.range.last + 1
        }
        appendPlain(content.substring(cursor))
        hasRenderedLine = true
    }

    if (inCode && codeStart >= 0 && out.length > codeStart) {
        out.addStyle(
            androidx.compose.ui.text.SpanStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFF0F0F0),
                background = Color(0xFF242424)
            ),
            codeStart, out.length
        )
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
    chatTitle: String,
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
    onBrowser: (String?) -> Unit,
    onTasks: () -> Unit,
    onMenuAction: (String) -> Unit,
    onStop: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
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
    // Scroll only when transcript length changes. Status updates can arrive frequently while
    // a tool/model is running; tying animation to status causes repeated scroll work and fights
    // users who are reading earlier messages.
    LaunchedEffect(lines.lastOrNull()?.id, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    val busy = status.contains("think", true) || status.contains("work", true) || status.contains("act", true)
    Column(Modifier.fillMaxSize().background(ChatInk).imePadding()) {
        // Editorial header: quiet branding, no oversized pill around every element.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF121212))
                    .border(1.dp, ChatHairline, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowBack, null, tint = ChatCopy, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chatTitle, color = ChatCopy, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(statusDotColor))
                    Text(status.replaceFirstChar { it.uppercase() }.take(24), color = ChatMuted, fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier.padding(end = 3.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Start a new chat"; role = Role.Button }
                    .clickable(onClick = onNewChat).padding(horizontal = 6.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Rounded.Add, null, tint = ChatCopy, modifier = Modifier.size(15.dp))
                Text("New", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                modifier = Modifier.padding(end = 3.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Open chat history"; role = Role.Button }
                    .clickable(onClick = onHistory).padding(horizontal = 5.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("History", color = ChatMuted, fontSize = 11.sp)
            }
            Box {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .clickable { menuExpanded = true }
                        .semantics { contentDescription = "More chat options"; role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⋮", color = ChatCopy, fontSize = 24.sp, lineHeight = 24.sp)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(Color(0xFF10141B))
                ) {
                    fun choose(action: String) {
                        menuExpanded = false
                        onMenuAction(action)
                    }
                    DropdownMenuItem(text = { Text("New Chat", color = ChatCopy) }, onClick = { choose("new_chat") })
                    DropdownMenuItem(text = { Text("Rename chat", color = ChatCopy) }, onClick = { choose("rename_chat") })
                    DropdownMenuItem(text = { Text("Model", color = ChatCopy) }, onClick = { choose("model") })
                    DropdownMenuItem(text = { Text("Clear Chat", color = Color(0xFFE8A6A6)) }, onClick = { choose("clear_chat") })
                    DropdownMenuItem(text = { Text("Open Terminal", color = ChatCopy) }, onClick = { choose("terminal") })
                    DropdownMenuItem(text = { Text("Open Browser", color = ChatCopy) }, onClick = { choose("browser") })
                    DropdownMenuItem(text = { Text("Browse Chat Files", color = ChatCopy) }, onClick = { choose("chat_files") })
                    DropdownMenuItem(text = { Text("Skills in Session", color = ChatCopy) }, onClick = { choose("skills") })
                    DropdownMenuItem(text = { Text("MCPs in Session", color = ChatCopy) }, onClick = { choose("mcps") })
                    DropdownMenuItem(text = { Text("Memories in Session", color = ChatCopy) }, onClick = { choose("memories") })
                    DropdownMenuItem(text = { Text("Token Usage", color = ChatCopy) }, onClick = { choose("token_usage") })
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(modeChipFill)
                    .border(1.dp, ChatHairline, RoundedCornerShape(9.dp))
                    .semantics { contentDescription = if (commandMode) "Switch to Talk mode" else "Switch to Command mode"; role = Role.Button }
                    .clickable { onModeChange(!commandMode) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
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
                    .padding(horizontal = 18.dp, vertical = 14.dp),
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
                        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF121212)).border(1.dp, ChatHairline, RoundedCornerShape(12.dp))
                            .clickable { onSend(item.third) }.padding(horizontal = 12.dp, vertical = 11.dp),
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
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 20.dp)
            ) {
                itemsIndexed(lines, key = { _, item -> item.id }) { _, item -> ChatBubble(item, onBrowser) }
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
                Text("Remove", color = ChatCopy, fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = "Remove attached image"; role = Role.Button }.clickable(onClick = onRemoveImage).padding(7.dp))
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
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (commandMode) "Describe a task for Orion" else "Message Orion" },
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
                    Text("Live voice", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = "Start live voice"; role = Role.Button }.clickable(onClick = onVoice))
                    Text("Browser", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = "Open browser"; role = Role.Button }.clickable { onBrowser(null) })
                    Text("Tasks", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = "Open tasks"; role = Role.Button }.clickable(onClick = onTasks))
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

private enum class OrionReplyType { NORMAL, RESEARCH, CODE, TASK }

private fun classifyOrionReply(item: OrionChatLine): OrionReplyType {
    if (item.kind == "note") return OrionReplyType.TASK
    val text = item.text
    val lower = text.lowercase()
    if (Regex("(?s)```(?:kotlin|java|python|py|javascript|js|typescript|ts|bash|sh|json|xml|html|css|sql|rust|go)?\\s*\\n").containsMatchIn(text) ||
        (text.count { it == '\u0060' } >= 2 && (lower.contains("fun main") || lower.contains("def ") || lower.contains("import ") || lower.contains("class ")))) {
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
private fun ChatBubble(item: OrionChatLine, onOpenBrowser: (String?) -> Unit) {
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
                                OrionReplyType.RESEARCH -> Color(0xFF151515)
                                OrionReplyType.CODE -> Color(0xFF101010)
                                OrionReplyType.TASK -> Color(0xFF171717)
                                else -> Color.Transparent
                            })
                            .border(1.dp, when (replyType) {
                                OrionReplyType.RESEARCH -> Color(0xFF383838)
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
                                OrionReplyType.RESEARCH -> Color(0xFFD2D2D2)
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
                when {
                    note && item.text.startsWith("[SHELL:") -> ShellActivityCard(item.text)
                    note && item.text.startsWith("[BROWSER:") -> BrowserActivityCard(item.text, onOpenBrowser)
                    note && item.text.startsWith("[TASK:") -> TaskActivityCard(item.text)
                    else -> Text(
                        text = if (!mine && !note) formatOrionMarkdown(item.text) else AnnotatedString(item.text),
                        color = if (note) ChatMuted else ChatCopy,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.clickable(enabled = !note) { actionsVisible = !actionsVisible }
                    )
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
private fun TaskActivityCard(raw: String) {
    val state = Regex("^\\[TASK:([A-Z_]+)]").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val failed = state == "FAILED"
    val running = state == "RUNNING" || state == "EXECUTE"
    val blocked = state == "BLOCKED"
    val cancelled = state == "CANCELLED"
    val recovering = state == "RECOVER"
    val detail = raw.substringAfter("\n", raw).trim()
    val label = when {
        failed -> "STEP FAILED"
        running -> "STEP RUNNING"
        blocked -> "WAITING · NEEDS ATTENTION"
        cancelled -> "TASK CANCELLED"
        recovering -> "RECOVERING · REPLANNING"
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
