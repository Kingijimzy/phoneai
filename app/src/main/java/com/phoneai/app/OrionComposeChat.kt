package com.phoneai.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
private val ORION_BULLET_PATTERN = Regex("""^(\s*)(?:([-*+])\s+|(\d+[.)])\s+)(.+)$""")
private val ORION_TASK_PATTERN = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.+)$""")
private val ORION_INLINE_TOKEN_PATTERN = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)|\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|(?<![*\w])\*([^*\n]+)\*(?!\w)|(https?://[^\s)]+)""")

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
    var codeLang = ""
    var codeLines = 0
    var hasRenderedLine = false

    // Code never renders inline: a fenced block collapses to one muted line, and the full text opens from a card.
    fun appendCodeSummary() {
        if (hasRenderedLine) out.append("\n")
        val start = out.length
        out.append("▣ Code" + (if (codeLang.isNotBlank()) " · $codeLang" else "") + " · $codeLines line${if (codeLines == 1) "" else "s"} — open the card")
        out.addStyle(androidx.compose.ui.text.SpanStyle(color = OrionUiPalette.muted, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, out.length)
        hasRenderedLine = true
    }

    lines.forEach { raw ->
        if (raw.trimStart().startsWith("```")) {
            if (!inCode) {
                inCode = true
                codeLang = raw.trimStart().removePrefix("```").trim().lowercase()
                codeLines = 0
            } else {
                appendCodeSummary()
                inCode = false
            }
            return@forEach
        }
        if (inCode) { codeLines++; return@forEach }

        if (hasRenderedLine) out.append("\n")
        val lineStart = out.length
        val line = raw.trimEnd()
        val heading = ORION_HEADING_PATTERN.find(line)
        val task = ORION_TASK_PATTERN.find(line)
        val bullet = ORION_BULLET_PATTERN.find(line)
        val quote = line.trimStart().startsWith(">")
        val rule = line.trim() in setOf("---", "***", "___")
        val indent = (task?.groupValues?.get(1) ?: bullet?.groupValues?.get(1) ?: "")
            .length.div(2).coerceAtMost(3)
        val indentPrefix = "\u00A0".repeat(indent * 2)
        val content = when {
            heading != null -> heading.groupValues[2]
            task != null -> indentPrefix + (if (task.groupValues[2].equals("x", true)) "☑  " else "☐  ") + task.groupValues[3]
            bullet != null -> indentPrefix + (bullet.groupValues[3].ifBlank { "•" }) + "  " + bullet.groupValues[4]
            quote -> "│  " + line.trimStart().removePrefix(">").trimStart()
            rule -> "────────────────────────"
            else -> line
        }
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
                            color = OrionUiPalette.secondaryText,
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
                        androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                        start, out.length
                    )
                }
                match.groups[5] != null -> {
                    out.append(match.groupValues[5])
                    out.addStyle(androidx.compose.ui.text.SpanStyle(color = OrionUiPalette.secondaryText, fontWeight = FontWeight.Medium), start, out.length)
                }
                match.groups[6] != null -> {
                    out.append(match.groupValues[6])
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        start, out.length
                    )
                }
                else -> {
                    val url = match.groupValues[7].trimEnd('.', ',', ';')
                    out.append(url)
                    out.addStringAnnotation("URL", url, start, out.length)
                    out.addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = OrionUiPalette.secondaryText,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        ), start, out.length
                    )
                }
            }
            cursor = match.range.last + 1
        }
        appendPlain(content.substring(cursor))
        if (quote && out.length > lineStart) {
            out.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = OrionUiPalette.muted,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                lineStart, out.length
            )
        }
        if (rule && out.length > lineStart) {
            out.addStyle(androidx.compose.ui.text.SpanStyle(color = OrionUiPalette.border), lineStart, out.length)
        }
        if (task != null && out.length > lineStart) {
            val markerStart = lineStart + indentPrefix.length
            out.addStyle(androidx.compose.ui.text.SpanStyle(color = OrionUiPalette.accent), markerStart, (markerStart + 1).coerceAtMost(out.length))
        }
        hasRenderedLine = true
    }

    if (inCode) appendCodeSummary()
    return out.toAnnotatedString()
}

@Composable
internal fun OrionComposeActivityCard(event: OrionActivityEvent) {
    var expanded by rememberSaveable(event.eventId) { mutableStateOf(false) }
    val active = event.status == OrionActivityStatus.ACTIVE
    val accent = when (event.status) {
        OrionActivityStatus.ACTIVE -> Color(0xFFD1D1D1)
        OrionActivityStatus.WAITING -> Color(0xFFE0C58A)
        OrionActivityStatus.SUCCEEDED -> Color(0xFFB8CDBD)
        OrionActivityStatus.FAILED -> Color(0xFFE09B9B)
        else -> Color(0xFFAAAAAA)
    }
    val displayTitle = OrionActivityPresentation.title(event)
    val displaySummary = OrionActivityPresentation.summary(event)
    val safeEvidence = OrionActivityPresentation.evidence(event.evidence)
    val terminalOutput = safeEvidence.lastOrNull { it.contains("Python terminal", true) || it.contains("TASK:OUTPUT", true) }
        ?: displaySummary
    val alpha by animateFloatAsState(
        targetValue = if (active) 0.65f else 1f,
        animationSpec = tween(180),
        label = "task-status-alpha"
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(9.dp))
            .semantics {
                contentDescription = buildString {
                    append(event.title)
                    append(", ")
                    append(event.status.name.lowercase())
                    if (event.summary.isNotBlank()) append(". " + event.summary)
                    append(if (expanded) ". Hide task details" else ". Show task details")
                }
                role = Role.Button
            }
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent.copy(alpha = if (active) alpha else 1f)))
            Spacer(Modifier.width(7.dp))
            Text(event.title, color = OrionUiPalette.text, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(
                when (event.status) {
                    OrionActivityStatus.ACTIVE -> "RUNNING"
                    OrionActivityStatus.WAITING -> if (event.kind == OrionActivityKind.CODING) "VERIFYING" else "WAITING"
                    OrionActivityStatus.SUCCEEDED -> "DONE"
                    OrionActivityStatus.FAILED -> "FAILED"
                    OrionActivityStatus.CANCELLED -> "STOPPED"
                    OrionActivityStatus.INTERRUPTED -> "PAUSED"
                }, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold
            )
            Text(if (expanded) "LESS" else "DETAILS", color = OrionUiPalette.muted, fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold)
        }
        // Status accent only. This is deliberately not a percentage-complete bar.
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 13.dp)
                .height(2.dp).clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = if (active) 0.55f else 1f))
        )
        val steps = OrionActivityTimeline.progressSteps(safeEvidence)
        if (steps.isNotEmpty()) {
            val shown = if (expanded) steps.takeLast(14) else steps.takeLast(4)
            val finished = steps.count { it.state == "DONE" }
            val verifying = steps.count { it.state == "VERIFYING" || it.state == "WAITING" }
            Text(
                "WHAT ORION DID  ·  $finished/${steps.size} steps completed" +
                    (if (verifying > 0) "  ·  $verifying to verify" else "") +
                    (event.elapsedMs?.let { "  ·  " + OrionActivityTimeline.elapsedLabel(0L, it) } ?: ""),
                color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 13.dp, top = 6.dp)
            )
            shown.forEach { step ->
                val mark = when (step.state) {
                    "DONE" -> "✓"; "FAILED" -> "✕"; "WAITING", "VERIFYING" -> "…"
                    "CANCELLED" -> "■"; "INTERRUPTED" -> "Ⅱ"; else -> "▸"
                }
                val tint = when (step.state) {
                    "DONE" -> OrionUiPalette.success; "FAILED" -> OrionUiPalette.danger
                    "WAITING", "VERIFYING" -> OrionUiPalette.warning
                    "RUNNING" -> OrionUiPalette.text; else -> OrionUiPalette.muted
                }
                Column(Modifier.padding(start = 13.dp, top = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mark, color = tint.copy(alpha = if (step.state == "RUNNING") alpha else 1f),
                            fontSize = 10.sp, modifier = Modifier.width(14.dp))
                        Text(step.title, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(step.state, color = tint, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (step.detail.isNotBlank()) {
                        Text(step.detail, color = OrionUiPalette.muted, fontSize = 9.sp, lineHeight = 12.sp,
                            maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp, top = 1.dp))
                    }
                }
            }
        } else if (displaySummary.isNotBlank()) {
            Text(displaySummary, color = OrionUiPalette.muted, fontSize = 9.sp,
                maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 13.dp, top = 4.dp))
        }
        val terminalEvidence = event.typedEvidence.filterIsInstance<OrionActivityEvidence.Terminal>().lastOrNull()
        if (terminalEvidence != null) {
            Text(
                "$ " + terminalEvidence.commandLabel,
                color = OrionUiPalette.secondaryText, fontSize = 9.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(start = 13.dp, top = 4.dp)
            )
            if (terminalEvidence.outputTail.isNotBlank()) {
                Text(
                    terminalEvidence.outputTail.takeLast(400),
                    color = OrionUiPalette.success, fontSize = 9.sp, lineHeight = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 2.dp)
                        .clip(RoundedCornerShape(5.dp)).background(OrionUiPalette.background).padding(6.dp)
                )
            }
            terminalEvidence.exitCode?.let { code ->
                Text("exit $code", color = if (code == 0) OrionUiPalette.success else OrionUiPalette.danger,
                    fontSize = 8.sp, modifier = Modifier.padding(start = 13.dp, top = 2.dp))
            }
        } else if (event.kind == OrionActivityKind.TOOL && (event.title.contains("terminal", true) || event.summary.contains("TASK:OUTPUT", true))) {
            Text(
                terminalOutput.lineSequence().lastOrNull().orEmpty().takeLast(180).ifBlank { "Starting process…" },
                color = OrionUiPalette.success, fontSize = 9.sp, lineHeight = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 13.dp, top = 3.dp)
            )
        } else if (event.summary.isNotBlank()) {
            Text(event.summary, color = OrionUiPalette.muted, fontSize = 9.sp,
                maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 13.dp, top = 2.dp))
        }
        if (expanded) {
            event.evidence.takeLast(10).forEach { line ->
                Text(line, color = OrionUiPalette.muted, fontSize = 8.sp, lineHeight = 11.sp)
            }
            Text("Task ${event.taskId}", color = OrionUiPalette.muted, fontSize = 8.sp)
        }
    }
}

internal fun isCompactExecutionNote(item: OrionChatLine): Boolean {
    if (item.kind != "note") return false
    val text = item.text
    // Every task lifecycle note is shown by the single progress card and step strip, not as its own card.
    if (text.startsWith("[TASK:") && !text.startsWith("[TASK:WARNING]")) return true
    return listOf(
        "[TASK:RUNNING]", "[TASK:OUTPUT]", "[TASK:APPROVAL]", "[TASK:RECOVER]",
        "[TASK:FAILED]", "[TASK:COMPLETE]", "[TASK:CANCELLED]", "[TASK:INTERRUPTED]",
        "[TOOL:", "[TOOL_RESULT:", "[APPROVAL:", "[EVENT:", "[SPECIALIST:"
    ).any { text.startsWith(it) }
}

private val ChatInk = OrionUiPalette.background
private val ChatPanel = OrionUiPalette.panel
private val ChatPanelRaised = OrionUiPalette.raised
private val ChatMint = OrionUiPalette.accent
private val ChatCopy = OrionUiPalette.text
private val ChatMuted = OrionUiPalette.muted
private val ChatHairline = OrionUiPalette.border

data class OrionChatLine(
    val id: Long,
    val kind: String,
    val text: String,
    val imageUri: Uri? = null,
    /** Typed at the message boundary; legacy rows are adapted once when restored. */
    val content: OrionMessageContent = OrionMessageContent.fromLegacy(kind, text)
)

@Composable
internal fun OrionComposeChatScreen(
    lines: List<OrionChatLine>,
    activityEvents: List<OrionActivityEvent> = emptyList(),
    attachments: List<OrionChatAttachmentStore.Attachment> = emptyList(),
    onOpenAttachment: (OrionChatAttachmentStore.Attachment) -> Unit = {},
    onShareAttachment: (OrionChatAttachmentStore.Attachment) -> Unit = {},
    status: String,
    orbMode: OrbView.Mode = OrbView.Mode.IDLE,
    orbMicLevel: Float = 0f,
    orbSpeechAmplitude: Float = 0f,
    orbWordPulse: Float = 0f,
    companionLook: Int,
    onCompanionLookChanged: (Int) -> Unit,
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
    val latestActivityEvent = activityEvents.maxByOrNull { it.timestampMs }
    val activeActivityEvent = activityEvents
        .filter { it.status == OrionActivityStatus.ACTIVE || it.status == OrionActivityStatus.WAITING }
        .maxByOrNull { it.timestampMs }
    val displayStatus = activeActivityEvent?.let(::companionStatusForEvent) ?: status
    val companionActivity = activeActivityEvent?.let(::companionActivityForEvent)
        ?: companionActivityForChatStatus(status)
    var menuExpanded by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var sheetTick by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shownProvider = remember(sheetTick, providerLabel) { if (sheetTick == 0) providerLabel else Prefs.provider(ctx, false).label }
    if (showModelSheet) OrionModelSheet(onDismiss = { showModelSheet = false }, onNeedKey = onProviderClick, onChanged = { sheetTick++ })
    var inlineBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val composerFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    fun submitDraft() {
        if (listOf("think", "work", "act", "brows", "build", "draw", "run", "delegat").any { displayStatus.contains(it, true) }) {
            onStop()
            return
        }
        if (draft.isBlank() && selectedImages.isEmpty()) return
        val value = draft.trim()
        val original = editingOriginal
        if (original != null) {
            if (value.isNotBlank()) onEditSend(original, value)
        } else {
            onSend(value)
        }
        draft = ""
        editingOriginal = null
    }
    val effectiveOrbMode = orionModeForStatus(displayStatus, orbMode)
    val statusDotColor by animateColorAsState(
        targetValue = when (effectiveOrbMode) {
            OrbView.Mode.IDLE -> OrionUiPalette.muted
            OrbView.Mode.LISTENING -> Color(0xFF62CBE8)
            OrbView.Mode.THINKING -> Color(0xFF9B91F2)
            OrbView.Mode.SPEAKING -> Color(0xFF62D6C5)
            OrbView.Mode.ACTING -> Color(0xFFFFB86B)
            OrbView.Mode.WAITING_APPROVAL -> Color(0xFFFFC857)
            OrbView.Mode.ERROR -> Color(0xFFFF7777)
        },
        animationSpec = tween(durationMillis = 260),
        label = "chat-status-dot"
    )
    // A restrained pulse gives Orion visible presence while it is listening, thinking,
    // speaking or acting. Idle stays still so the interface never feels artificially busy.
    val presenceActive = listOf("listen", "think", "speak", "work", "act", "brows", "build", "draw", "run", "delegat")
        .any { displayStatus.contains(it, true) }
    val presenceTransition = rememberInfiniteTransition(label = "orion-presence")
    val presenceAlpha by presenceTransition.animateFloat(
        initialValue = if (presenceActive) 0.22f else 0f,
        targetValue = if (presenceActive) 0.72f else 0f,
        animationSpec = infiniteRepeatable(tween(1050), repeatMode = RepeatMode.Reverse),
        label = "orion-presence-alpha"
    )
    val presenceScale by presenceTransition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1050), repeatMode = RepeatMode.Reverse),
        label = "orion-presence-scale"
    )
    val modeChipFill by animateColorAsState(
        targetValue = if (commandMode) OrionUiPalette.border else OrionUiPalette.panel,
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
    var showJumpToBottom by remember { mutableStateOf(false) }
    val chatScrollScope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            layout.totalItemsCount > 0 && layout.totalItemsCount - 1 - lastVisible > 2
        }.collect { showJumpToBottom = it }
    }
    LaunchedEffect(lines.lastOrNull()?.id, (lines.lastOrNull()?.text?.length ?: 0) / 120) {
        // Only follow new content when the reader is already near the end. Use the LazyColumn's
        // actual item count because task/attachment cards are additional list items.
        if (lines.isNotEmpty()) {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            val nearBottom = lastVisible == null || layout.totalItemsCount - 1 - lastVisible <= 2
            if (lines.lastOrNull()?.kind == "me" || nearBottom) {
                kotlinx.coroutines.yield()
                val lastItem = listState.layoutInfo.totalItemsCount - 1
                if (lastItem >= 0) listState.animateScrollToItem(lastItem)
            }
        }
    }
    val busy = listOf("think", "work", "act", "brows", "build", "draw", "run", "delegat")
        .any { displayStatus.contains(it, true) }
    var showTranscript by remember { mutableStateOf(false) }
    var showRunSheet by remember { mutableStateOf(false) }
    var showEverything by remember { mutableStateOf(false) }
    val socialIds = remember(lines.size) { runCatching { OrionSocialConnector.connectedIds(ctx) }.getOrDefault(emptyList()) }
    val appNodes = remember(lines.size) { OrionWorkspaceApps.connectedNodes(ctx) }
    val linked = remember(socialIds, appNodes) { OrionNodes.linked(socialIds, appNodes) }
    val runSteps = OrionRunLog.steps
    val currentRun = OrionRunLog.currentRun(runSteps)
    val runningTitle = runSteps.lastOrNull { it.status == OrionRunLog.Status.RUNNING }?.title
    val pillText = OrionPanelText.statusLine(effectiveOrbMode, busy, runningTitle)
    val lastUserText = lines.lastOrNull { it.kind == "me" }?.text.orEmpty()
    if (showEverything) OrionEverythingSheet(onDismiss = { showEverything = false }, onAction = { if (it == "history") onHistory() else onMenuAction(it) })
    if (showRunSheet) OrionRunSheet(onDismiss = { showRunSheet = false }, onResume = { onSend(it) })
    Column(Modifier.fillMaxSize().background(Color(0xFF000000)).imePadding()) {
        // Top bar: back, title + live link count, LIVE (model) pill, Activity toggle, overflow.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(OrionUiPalette.panel).border(1.dp, ChatHairline, CircleShape)
                    .semantics { contentDescription = "Back"; role = Role.Button }.clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowBack, null, tint = ChatCopy, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NEURAL NETWORK", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1)
                Text(OrionNodes.headline(linked), color = ChatMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (providerReady) ChatHairline else OrionUiPalette.warning, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { showModelSheet = true }
                    .semantics { contentDescription = "Choose model; $shownProvider $modelLabel"; role = Role.Button }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusDotColor.copy(alpha = if (presenceActive) .5f + presenceAlpha * .5f else .9f)))
                Spacer(Modifier.width(6.dp))
                Text("LIVE", color = OrionUiPalette.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (showTranscript) "Network" else "Activity", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.pressScale(0.94f).clip(RoundedCornerShape(50)).background(OrionUiPalette.raised).border(1.dp, ChatHairline, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { showTranscript = !showTranscript }.padding(horizontal = 13.dp, vertical = 7.dp))
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = "More options"; role = Role.Button }.clickable { menuExpanded = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.MoreVert, "More options", tint = ChatCopy, modifier = Modifier.size(19.dp)) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(OrionUiPalette.panel)) {
                    fun choose(action: String) {
                        menuExpanded = false
                        onMenuAction(action)
                    }
                    @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) =
                        DropdownMenuItem(
                            text = { Text(label, color = if (danger) OrionUiPalette.danger else ChatCopy) },
                            leadingIcon = { Icon(icon, null, tint = if (danger) OrionUiPalette.danger else ChatMuted, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; onClick() })
                    item(if (commandMode) "Talk mode" else "Command mode", Icons.Rounded.Bolt) { onModeChange(!commandMode) }
                    item("New chat", Icons.Rounded.Add) { onNewChat() }
                    item("History", Icons.Rounded.History) { onHistory() }
                    item("Rename", Icons.Rounded.Edit) { onMenuAction("rename_chat") }
                    item("Model", Icons.Rounded.Tune) { onMenuAction("model") }
                    item("Everything", Icons.Rounded.Apps) { showEverything = true }
                    item("Clear chat", Icons.Rounded.Delete, danger = true) { onMenuAction("clear_chat") }
                }
            }
        }

        if (!showTranscript) {
            OrionNetworkStage(
                linked = linked, busy = busy, orbMode = effectiveOrbMode,
                orbMicLevel = orbMicLevel, orbSpeechAmplitude = orbSpeechAmplitude, orbWordPulse = orbWordPulse,
                onVoice = onVoice, modifier = Modifier.weight(1f).fillMaxWidth()
            )
            OrionTaskStrip(title = lastUserText.replace('\n', ' ').take(60), run = currentRun, onOpen = { showRunSheet = true })
            Spacer(Modifier.height(10.dp))
            OrionReplyPanel(lines, onOpenTranscript = { showTranscript = true })
            Spacer(Modifier.height(10.dp))
            OrionStatusPill(pillText, live = busy || effectiveOrbMode != OrbView.Mode.IDLE, onMic = onVoice, onEnd = { if (busy) onStop() else onVoice() })
            Spacer(Modifier.height(8.dp))
        } else {
            OrionLiveMetricsBar()
            val visibleLines = lines.filterNot(::isCompactExecutionNote)
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 22.dp)
            ) {
                itemsIndexed(visibleLines, key = { _, item -> item.id }) { index, item ->
                    val previousUserMessage = if (item.kind == "ai") {
                        visibleLines.take(index).lastOrNull { it.kind == "me" }?.text
                    } else null
                    androidx.compose.foundation.layout.Box(Modifier.orionEnter()) { ChatBubble(
                        item = item,
                        onOpenBrowser = { url -> inlineBrowserUrl = url ?: "https://duckduckgo.com/" },
                        attachments = attachments,
                        onOpenAttachment = onOpenAttachment,
                        onShareAttachment = onShareAttachment,
                        onRetry = previousUserMessage?.takeIf { it.isNotBlank() }?.let { prompt ->
                            { onRetry(prompt) }
                        },
                        onEdit = if (item.kind == "me") { message ->
                            editingOriginal = message
                            draft = message
                            composerFocusRequester.requestFocus()
                            keyboardController?.show()
                        } else null
                    ) }
                }
                val inlineArtifactIds = lines.flatMap { line ->
                    line.content.blocks.filterIsInstance<OrionMessageBlock.GeneratedFile>().map { it.artifactId }
                }.toSet()
                val unattached = attachments.distinctBy { it.id }.filterNot { it.id in inlineArtifactIds }
                if (unattached.isNotEmpty()) {
                    item(key = "orion-chat-attachments-heading") {
                        Text("ATTACHMENTS", color = OrionUiPalette.muted, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
                    }
                    itemsIndexed(unattached, key = { _, item -> "chat-attachment-${item.id}" }) { _, item ->
                        OrionChatAttachmentCard(item, onOpenAttachment, onShareAttachment)
                    }
                }
                item(key = "orion-interruptions") { OrionInterruptionBanner() }
                // One live summary per task, updated in place; steps open their source.
                item(key = "orion-activity-hub") { OrionActivityHubCard(onOpenAll = { showRunSheet = true }) }
                // Task progress is represented by the single Activity Hub card above.
                // Do not append a second copy of the latest activity event to the transcript.
                val assistantAlreadyStreaming = lines.lastOrNull()?.let {
                    it.kind == "ai" && it.text.isNotBlank()
                } == true
                if (busy && !assistantAlreadyStreaming) {
                    item(key = "orion-thinking-indicator") { ThinkingIndicator(displayStatus, effectiveOrbMode) }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJumpToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 14.dp),
                enter = fadeIn(tween(120)),
                exit = androidx.compose.animation.fadeOut(tween(100))
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(OrionUiPalette.raised)
                        .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button) {
                            val lastItem = listState.layoutInfo.totalItemsCount - 1
                            if (lastItem >= 0) chatScrollScope.launch { listState.animateScrollToItem(lastItem) }
                        }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.ExpandMore, null, tint = OrionUiPalette.text, modifier = Modifier.size(17.dp))
                    Text("Latest", color = OrionUiPalette.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            }
        }

        OrionSlashPalette(draft, onPick = { draft = "" })
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
                            Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape)
                                .background(OrionUiPalette.background)
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
                Text("Clear", color = ChatMuted, fontSize = 10.sp, modifier = Modifier.heightIn(min = 44.dp)
                    .clickable(role = Role.Button) { selectedImages.indices.reversed().forEach(onRemoveImage) }
                    .semantics { contentDescription = "Remove all attached images"; role = Role.Button }
                    .padding(horizontal = 8.dp, vertical = 10.dp))
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            AnimatedVisibility(
                visible = editingOriginal != null,
                enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { it / 3 },
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(120))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
                        .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, RoundedCornerShape(9.dp))
                        .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("EDITING MESSAGE", color = OrionUiPalette.accent, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Text("Your revised message will replace the previous one", color = ChatMuted,
                            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("CANCEL", color = ChatCopy, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(7.dp))
                            .clickable(role = Role.Button) { editingOriginal = null; draft = "" }
                            .semantics { contentDescription = "Cancel message editing"; role = Role.Button }
                            .padding(horizontal = 10.dp, vertical = 12.dp))
                }
            }
            if (draft.isEmpty() && editingOriginal == null) OrionQuickBar(onEverything = { showEverything = true }, modifier = Modifier.padding(bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(OrionUiPalette.raised)
                    .border(1.dp, OrionUiPalette.border.copy(alpha = 0.7f), RoundedCornerShape(28.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onPickImage)
                        .semantics { contentDescription = "Attach file or image"; role = Role.Button },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, null, tint = ChatMuted, modifier = Modifier.size(21.dp)) }
                Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp)) {
                    if (draft.isEmpty()) Text(if (busy || effectiveOrbMode != OrbView.Mode.IDLE) "Type to Orion while you talk…" else if (commandMode) "Describe the task…" else "Ask Orion anything…", color = ChatMuted, fontSize = 15.sp)
                    BasicTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(composerFocusRequester).semantics { contentDescription = if (commandMode) "Describe a task for Orion" else "Message Orion" },
                        textStyle = TextStyle(color = ChatCopy, fontSize = 15.sp, lineHeight = 21.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = if (commandMode) androidx.compose.ui.text.input.KeyboardCapitalization.None else androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            autoCorrect = !commandMode,
                            imeAction = ImeAction.Default
                        ),
                        maxLines = 5
                    )
                }
                if (draft.isBlank() && selectedImages.isEmpty() && !busy) {
                    ComposerButton(Icons.Rounded.GraphicEq, "Start live voice", onVoice, secondary = false)
                } else {
                    ComposerButton(
                        if (busy && draft.isBlank()) Icons.Rounded.Stop else Icons.Rounded.Send,
                        if (busy && draft.isBlank()) "Stop response" else "Send message",
                        { if (busy && draft.isBlank()) onStop() else submitDraft() },
                        secondary = busy && draft.isBlank()
                    )
                }
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
                .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(18.dp)).padding(10.dp)
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(OrionUiPalette.panel).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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


/** Archived transcripts reuse the live chat message renderer to preserve rich formatting. */
@Composable
internal fun OrionArchivedConversationScreen(
    title: String,
    date: String,
    messages: List<Pair<String, String>>,
    structuredMessages: List<OrionChatLine> = emptyList(),
    activityEvents: List<OrionConversationRepository.ChatEvent> = emptyList(),
    attachments: List<OrionChatAttachmentStore.Attachment> = emptyList(),
    onOpenAttachment: (OrionChatAttachmentStore.Attachment) -> Unit = {},
    onShareAttachment: (OrionChatAttachmentStore.Attachment) -> Unit = {},
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
    onOpenBrowser: (String?) -> Unit
) {
    val renderedMessages = if (structuredMessages.isNotEmpty()) structuredMessages else
        messages.mapIndexed { index, pair -> OrionChatLine(index.toLong(), pair.first, pair.second) }
    val activityCards = remember(activityEvents) {
        val projection = OrionActivityTimeline(maxEvents = 300)
        projection.replay(OrionActivityTimeline.fromPersisted(activityEvents))
        projection.compactForChat()
    }
    Column(Modifier.fillMaxSize().background(OrionUiPalette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "‹ History",
                color = OrionUiPalette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title.ifBlank { "Conversation" }, color = OrionUiPalette.text,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(date, color = OrionUiPalette.muted, fontSize = 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrionUiPalette.border))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 22.dp)
        ) {
            if (renderedMessages.isEmpty() && activityEvents.isEmpty()) {
                item(key = "archive-empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Text("This conversation has no saved messages or activity.", color = OrionUiPalette.muted, fontSize = 13.sp)
                    }
                }
            }
            itemsIndexed(renderedMessages, key = { index, item -> "archive-" + index + "-" + item.id }) { _, item ->
                ChatBubble(
                    item = item,
                    onOpenBrowser = onOpenBrowser,
                    attachments = attachments,
                    onOpenAttachment = onOpenAttachment,
                    onShareAttachment = onShareAttachment
                )
            }
            if (attachments.isNotEmpty()) {
                item(key = "archive-attachments-heading") {
                    Text("ATTACHMENTS", color = OrionUiPalette.muted, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
                }
                itemsIndexed(attachments, key = { _, item -> "archive-attachment-${item.id}" }) { _, item ->
                    OrionChatAttachmentCard(item, onOpenAttachment, onShareAttachment)
                }
            }
            if (activityEvents.isNotEmpty()) {
                item(key = "archive-activity-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ACTIVITY HISTORY", color = OrionUiPalette.secondaryText,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                        Text("Persisted execution receipts for this conversation",
                            color = OrionUiPalette.muted, fontSize = 11.sp)
                    }
                }
                itemsIndexed(activityCards, key = { _, event -> "archive-task-${event.eventId}" }) { _, event ->
                    OrionComposeActivityCard(event)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrionUiPalette.border))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(OrionUiPalette.raised)
                    .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
                    .clickable(onClick = onCopy).padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Copy transcript", color = OrionUiPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(OrionUiPalette.text)
                    .clickable(onClick = onContinue).padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Continue chat", color = OrionUiPalette.background, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}


/** Compose-native archive list; live and archived chat surfaces share the same visual tokens. */
@Composable
internal fun OrionChatHistoryScreen(
    chats: List<OrionSavedChat>,
    onClose: () -> Unit,
    onClearAll: () -> Unit,
    onOpen: (OrionSavedChat) -> Unit,
    onRename: (OrionSavedChat) -> Unit,
    onDelete: (OrionSavedChat) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(chats, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) chats else chats.filter { chat ->
            chat.title.lowercase().contains(needle) ||
                chat.messages.any { it.second.lowercase().contains(needle) }
        }
    }
    val dateFormat = remember { java.text.SimpleDateFormat("d MMM yyyy · h:mm a", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(OrionUiPalette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("CHAT HISTORY", color = OrionUiPalette.muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Your conversations", color = OrionUiPalette.text, fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(chats.size.toString() + " saved conversations · stored on this device",
                    color = OrionUiPalette.muted, fontSize = 11.sp)
            }
            Text("Clear all", color = OrionUiPalette.secondaryText, fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = chats.isNotEmpty(), onClick = onClearAll)
                    .padding(horizontal = 8.dp, vertical = 12.dp))
            Text("Close", color = OrionUiPalette.text, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(OrionUiPalette.border))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.panel)
                .border(1.dp, OrionUiPalette.borderStrong, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⌕", color = OrionUiPalette.muted, fontSize = 20.sp)
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (query.isBlank()) Text("Search conversations", color = OrionUiPalette.muted, fontSize = 13.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = OrionUiPalette.text, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotBlank()) {
                Text("Clear", color = OrionUiPalette.secondaryText, fontSize = 11.sp,
                    modifier = Modifier.clickable { query = "" }.padding(start = 8.dp))
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (chats.isEmpty()) "No saved conversations yet" else "No matching conversations",
                        color = OrionUiPalette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (chats.isEmpty()) "Start a conversation and Orion will keep it here."
                        else "Try another title or phrase from the conversation.",
                        color = OrionUiPalette.muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 22.dp)
            ) {
                itemsIndexed(filtered, key = { _, chat -> chat.sessionId }) { _, chat ->
                    val preview = chat.messages.lastOrNull()?.second.orEmpty().replace('\n', ' ').trim()
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                            .background(OrionUiPalette.panel)
                            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(13.dp))
                            .clickable { onOpen(chat) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.title.ifBlank { "Untitled conversation" }, color = OrionUiPalette.text,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text("Rename", color = OrionUiPalette.secondaryText, fontSize = 10.sp,
                                modifier = Modifier.clickable { onRename(chat) }.padding(horizontal = 7.dp, vertical = 7.dp))
                            Text("Delete", color = OrionUiPalette.muted, fontSize = 10.sp,
                                modifier = Modifier.clickable { onDelete(chat) }.padding(start = 5.dp, end = 2.dp, top = 7.dp, bottom = 7.dp))
                        }
                        Text(dateFormat.format(java.util.Date(chat.timestamp)) + "  ·  " +
                            chat.messages.size + " messages", color = OrionUiPalette.muted, fontSize = 10.sp)
                        Text(preview.ifBlank { "Open conversation to view messages" },
                            color = OrionUiPalette.secondaryText, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
