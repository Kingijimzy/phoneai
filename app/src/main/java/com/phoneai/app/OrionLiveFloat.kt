package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.roundToInt

/** What the floating card shows for one running step: a few trailing output lines, never raw dumps. */
internal object OrionLiveFloatPolicy {
    fun tail(output: String, lines: Int = 4, width: Int = 52): List<String> =
        output.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList().takeLast(lines).map { it.take(width) }

    private val writeTools = setOf("dev_write", "workspace_write", "save_file", "devspace")

    /** For file-writing steps: the path and the last lines of the code being written, read from the step's arguments. */
    fun codePreview(step: OrionRunLog.Step, lines: Int = 5): Pair<String, List<String>>? {
        if (step.tool !in writeTools) return null
        val path = Regex("\"(?:path|file|name)\"\\s*:\\s*\"([^\"]{1,80})\"").find(step.args)?.groupValues?.get(1) ?: return null
        val raw = Regex("\"(?:content|code|text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(step.args)?.groupValues?.get(1).orEmpty()
        val code = raw.replace("\\n", "\n").replace("\\t", "  ").replace("\\\"", "\"")
        return path to tail(code, lines)
    }

    fun headline(step: OrionRunLog.Step): String = step.title.ifBlank { step.tool }.replace(Regex("\\s+"), " ").take(40)
}

/** Small draggable live view of what Orion is doing right now (commands, installs, browsing, code). Tap to fold. */
@Composable
internal fun OrionLiveFloat(modifier: Modifier = Modifier) {
    val running = OrionRunLog.currentRun().lastOrNull { it.status == OrionRunLog.Status.RUNNING } ?: return
    var offset by remember { mutableStateOf(Offset.Zero) }
    var folded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.id) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.padding(10.dp).offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(if (folded) 150.dp else 230.dp).clip(shape)
            .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.borderStrong, shape)
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); offset += drag } }
            .clickable { folded = !folded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("●", color = OrionUiPalette.accent, fontSize = 9.sp)
            Text(OrionLiveFloatPolicy.headline(running), color = OrionUiPalette.text, fontSize = 11.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(OrionRunLog.durationLabel(OrionRunLog.durationMs(running, now)), color = OrionUiPalette.text, fontSize = 10.sp)
        }
        val preview = OrionLiveFloatPolicy.codePreview(running)
        if (!folded && preview != null) {
            Text("✎ " + preview.first, color = OrionUiPalette.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            preview.second.forEach { Text(it, color = OrionUiPalette.text.copy(alpha = .75f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Clip) }
        } else if (!folded) OrionLiveFloatPolicy.tail(running.output).forEach {
            Text(it, color = OrionUiPalette.text.copy(alpha = .75f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}
