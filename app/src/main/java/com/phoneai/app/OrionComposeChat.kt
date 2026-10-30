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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

internal fun normalizeOrionMarkdownTables(source: String): String {
    val input = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val output = mutableListOf<String>()
    var i = 0
    var inCode = false
    fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
    fun isSeparator(line: String): Boolean {
        val values = cells(line)
        return values.size >= 2 && values.all { it.matches(Regex(":?-{3,}:?")) }
    }
    while (i < input.size) {
        val line = input[i]
        if (line.trimStart().startsWith("\u0060\u0060\u0060")) {
            inCode = !inCode
            output += line
            i++
            continue
        }
        if (!inCode && line.count { it == '|' } >= 2 && i + 1 < input.size &&
            input[i + 1].count { it == '|' } >= 2 && isSeparator(input[i + 1])) {
            val headers = cells(line)
            i += 2
            var rows = 0
            while (i < input.size && input[i].count { it == '|' } >= 2) {
                val values = cells(input[i])
                val rendered = headers.indices.mapNotNull { column ->
                    val header = headers.getOrNull(column).orEmpty()
                    val value = values.getOrNull(column).orEmpty()
                    if (header.isBlank() || value.isBlank()) null else "**" + header + ":** " + value
                }
                if (rendered.isNotEmpty()) output += rendered.joinToString(" · ")
                i++
                rows++
            }
            if (rows == 0) output += headers.joinToString(" · ")
            continue
        }
        output += line
        i++
    }
    return output.joinToString("\n")
}

internal fun formatOrionMarkdown(source: String): AnnotatedString {
    val out = AnnotatedString.Builder()
    val lines = normalizeOrionMarkdownTables(source).split('\n')
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
                    out.addStringAnnotation("URL", match.groupValues[2], start, out.length)
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
                    val url = match.groupValues[6].trimEnd('.', ',', ';')
                    out.append(url)
                    out.addStringAnnotation("URL", url, start, out.length)
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
    selectedImages: List<Uri>,
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
    onRemoveImage: (Int) -> Unit,
    onVoice: () -> Unit,
    onBrowser: (String?) -> Unit,
    onTasks: () -> Unit,
    onMenuAction: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onEditSend: (String, String) -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var editingOriginal by rememberSaveable { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var inlineBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val composerFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
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
    LaunchedEffect(lines.lastOrNull()?.id, (lines.lastOrNull()?.text?.length ?: 0) / 80) {
        // Keep streaming replies in view in coarse chunks, not every token. Never yank the
        // reader away from older messages unless they are already near the bottom; always
        // reveal a newly sent user turn.
        if (lines.isNotEmpty()) {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            val nearBottom = lastVisible == null || layout.totalItemsCount - 1 - lastVisible <= 2
            if (lines.lastOrNull()?.kind == "me" || nearBottom) {
                listState.animateScrollToItem(lines.lastIndex)
            }
        }
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
                Text(chatTitle, color = ChatCopy, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(statusDotColor))
                    Text(status.replaceFirstChar { it.uppercase() }.take(24), color = ChatMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Start a new chat"; role = Role.Button }
                    .clickable(onClick = onNewChat),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Add, "New chat", tint = ChatCopy, modifier = Modifier.size(19.dp)) }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = "Open chat history"; role = Role.Button }
                    .clickable(onClick = onHistory),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.History, "Chat history", tint = ChatMuted, modifier = Modifier.size(18.dp)) }
            Box {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .semantics { contentDescription = "More chat options"; role = Role.Button }
                        .clickable { menuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.MoreVert, "More options", tint = ChatCopy, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .widthIn(min = 232.dp, max = 260.dp)
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xF2161616))
                        .border(1.dp, ChatHairline, RoundedCornerShape(18.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 7.dp)
                ) {
                    fun choose(action: String) {
                        menuExpanded = false
                        onMenuAction(action)
                    }
                    @Composable
                    fun section(title: String) {
                        Text(
                            title.uppercase(),
                            color = ChatMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.15.sp,
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 7.dp, bottom = 3.dp)
                        )
                    }
                    @Composable
                    fun action(title: String, key: String, destructive: Boolean = false) {
                        Row(
                            Modifier.fillMaxWidth().height(42.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { choose(key) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                color = if (destructive) Color(0xFFE3A0A0) else ChatCopy,
                                fontSize = 13.sp,
                                fontWeight = if (destructive) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    section("Conversation")
                    action("New chat", "new_chat")
                    action("Rename conversation", "rename_chat")
                    action("Choose model", "model")
                    action("Clear conversation", "clear_chat", destructive = true)
                    section("Workspace")
                    action("Open terminal", "terminal")
                    action("Open browser", "browser")
                    action("Attachment support", "chat_files")
                    section("Orion capabilities")
                    action("Skills in session", "skills")
                    action("Capabilities & tools", "capabilities")
                    action("Memories in session", "memories")
                    action("MCP servers in session", "mcps")
                    section("System & support")
                    action("Notification access", "notifications")
                    action("Alerts & briefings", "alerts")
                    action("Send feedback", "feedback")
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
                itemsIndexed(lines, key = { _, item -> item.id }) { index, item ->
                    val previousUserMessage = if (item.kind == "ai") {
                        lines.take(index).lastOrNull { it.kind == "me" }?.text
                    } else null
                    ChatBubble(
                        item = item,
                        onOpenBrowser = { url -> inlineBrowserUrl = url ?: "https://duckduckgo.com/" },
                        onRetry = previousUserMessage?.takeIf { it.isNotBlank() }?.let { prompt ->
                            { onRetry(prompt) }
                        },
                        onEdit = if (item.kind == "me") { message ->
                            editingOriginal = message
                            draft = message
                            composerFocusRequester.requestFocus()
                            keyboardController?.show()
                        } else null
                    )
                }
                if (busy) item(key = "orion-thinking-indicator") { ThinkingIndicator() }
            }
        }

        OrionStepStrip(lines)

        AnimatedVisibility(selectedImages.isNotEmpty(), enter = fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(180)) { it / 3 }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp)).background(ChatPanelRaised)
                    .border(1.dp, ChatHairline, RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedImages.take(4).forEachIndexed { index, uri ->
                    Box(Modifier.size(46.dp)) {
                        ImageThumbnail(uri, Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape)
                                .background(Color(0xFF080808))
                                .semantics { contentDescription = "Remove attached image ${index + 1}"; role = Role.Button }
                                .clickable { onRemoveImage(index) },
                            contentAlignment = Alignment.Center
                        ) { Text("×", color = ChatCopy, fontSize = 15.sp, lineHeight = 16.sp) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${selectedImages.size} image${if (selectedImages.size == 1) "" else "s"} attached", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Ready to analyse together", color = ChatMuted, fontSize = 11.sp)
                }
                Text("Clear", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.clickable { selectedImages.indices.reversed().forEach(onRemoveImage) }.padding(5.dp))
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
                        Text(providerLabel, color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Text(modelLabel, color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        modifier = Modifier.fillMaxWidth().focusRequester(composerFocusRequester).semantics { contentDescription = if (commandMode) "Describe a task for Orion" else "Message Orion" },
                        textStyle = TextStyle(color = ChatCopy, fontSize = 14.sp, lineHeight = 21.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (busy) {
                                onStop()
                            } else if (draft.isNotBlank() || selectedImages.isNotEmpty()) {
                                val value = draft.trim()
                                val original = editingOriginal
                                if (original != null && value.isNotBlank()) onEditSend(original, value) else onSend(value)
                                draft = ""
                                editingOriginal = null
                            }
                        }), maxLines = 5
                    )
                }
                ComposerButton(Icons.Rounded.AddPhotoAlternate, "Attach image", onPickImage, secondary = true)
                Spacer(Modifier.width(5.dp))
                ComposerButton(
                    if (busy) Icons.Rounded.Stop else Icons.Rounded.Send,
                    if (busy) "Stop response" else "Send message",
                    {
                        if (busy) {
                            onStop()
                        } else if (draft.isNotBlank() || selectedImages.isNotEmpty()) {
                            val value = draft.trim()
                            val original = editingOriginal
                            if (original != null && value.isNotBlank()) onEditSend(original, value) else onSend(value)
                            draft = ""
                            editingOriginal = null
                        }
                    },
                    secondary = busy
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 9.dp, start = 5.dp, end = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live voice", color = ChatMuted, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Start live voice"; role = Role.Button }
                        .clickable(onClick = onVoice).padding(horizontal = 5.dp, vertical = 7.dp))
                Text("Browser", color = ChatMuted, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Open browser preview in chat"; role = Role.Button }
                        .clickable { inlineBrowserUrl = "https://duckduckgo.com/" }.padding(horizontal = 5.dp, vertical = 7.dp))
                Text("Tasks", color = ChatMuted, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Open tasks"; role = Role.Button }
                        .clickable(onClick = onTasks).padding(horizontal = 5.dp, vertical = 7.dp))
                Spacer(Modifier.weight(1f))
                Text(if (busy) "WORKING" else if (commandMode) "COMMAND MODE" else "TALK MODE",
                    color = ChatMuted, fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1)
            }
        }
    }
    inlineBrowserUrl?.let { url ->
        ChatInlineBrowserDialog(
            initialUrl = url,
            onDismiss = { inlineBrowserUrl = null },
            onOpenFullBrowser = { target -> onBrowser(target) }
        )
    }
}

@Composable
private fun ChatInlineBrowserDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onOpenFullBrowser: (String) -> Unit
) {
    var address by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var currentUrl by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var pageTitle by rememberSaveable { mutableStateOf("Chat browser") }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun safeWebUrl(raw: String): String? {
        val candidate = raw.trim().let { if (it.startsWith("https://", true) || it.startsWith("http://", true)) it else "https://$it" }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) return null
        return uri.toString()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF101010)).border(1.dp, Color(0xFF383838), RoundedCornerShape(18.dp)).padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BROWSER", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text("OPEN FULL", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.clickable {
                    val target = safeWebUrl(currentUrl) ?: safeWebUrl(address) ?: "https://duckduckgo.com/"
                    onOpenFullBrowser(target)
                }.padding(6.dp))
                Text("DONE", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1D1D1D)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = TextStyle(color = ChatCopy, fontSize = 12.sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { safeWebUrl(address)?.let { currentUrl = it; webView?.loadUrl(it); loading = true } })
                )
                Text("GO", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    val target = safeWebUrl(address)
                    if (target != null) { currentUrl = target; webView?.loadUrl(target); loading = true }
                }.padding(4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("‹ BACK", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.clickable { if (webView?.canGoBack() == true) webView?.goBack() }.padding(4.dp))
                Text("RELOAD", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.clickable { webView?.reload(); loading = true }.padding(4.dp))
                Text(if (loading) "LOADING…" else pageTitle.take(42), color = ChatMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val uri = request.url
                                return uri.scheme !in setOf("http", "https")
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                if (!url.isNullOrBlank() && safeWebUrl(url) != null) { currentUrl = url; address = url }
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                pageTitle = view.title?.takeIf { it.isNotBlank() } ?: "Page loaded"
                                if (!url.isNullOrBlank() && safeWebUrl(url) != null) { currentUrl = url; address = url }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) pageTitle = title }
                        }
                        webView = this
                        safeWebUrl(initialUrl)?.let { loadUrl(it) }
                    }
                },
                update = { if (webView !== it) webView = it }
            )
            DisposableEffect(Unit) {
                onDispose { /* Keep the WebView alive only for this dialog; do not retain a hidden browsing surface. */ webView?.stopLoading(); webView?.destroy(); webView = null }
            }
        }
    }
}
