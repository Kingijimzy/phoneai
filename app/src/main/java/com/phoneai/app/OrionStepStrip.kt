package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val StripBg = Color(0xFF171717)
private val StripLine = Color(0xFF343434)
private val StripText = Color(0xFFF2F2F2)
private val StripMuted = Color(0xFF9B9B9B)
private val StripWarn = Color(0xFFE7A18F)

/**
 * Compact strip above the composer: the current tool step of the running task with its real status,
 * elapsed time and retry count, plus a pager over the steps of this task. Every value comes from
 * the chat note stream the agent emits, via [StepTracker]; nothing here is invented.
 */
@Composable
internal fun OrionStepStrip(lines: List<OrionChatLine>, modifier: Modifier = Modifier) {
    val stepLines = remember(lines.size, lines.lastOrNull()?.text) { lines.map { StepLine(it.id, it.kind, it.text) } }
    val steps = remember(stepLines) { StepTracker.currentTaskSteps(stepLines) }
    val taskState = remember(stepLines) { StepTracker.taskState(stepLines) }
    if (steps.isEmpty()) return

    // Keyed by the conversation's first line so switching chats never carries a pager position over.
    var index by remember(lines.firstOrNull()?.id) { mutableIntStateOf(steps.lastIndex) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    val anyActive = steps.any { it.active }
    // When a step is added, follow the newest unless the user is looking at one that is still running.
    LaunchedEffect(lines.firstOrNull()?.id, steps.size) { index = StepPager.follow(index.coerceIn(0, steps.lastIndex), steps) }
    LaunchedEffect(anyActive) { while (anyActive) { now = System.nanoTime(); delay(1000) } }

    val shown = index.coerceIn(0, steps.lastIndex)
    val step = steps[shown]
    val duration = step.durationLabel(now)
    val statusText = StepStatusText.of(step, taskState, RecoveryPolicy.MAX_READ_RETRIES)
    val failed = step.status == StepStatus.FAILED || step.status == StepStatus.CANCELLED
    val pagerLabel = StepPager.label(shown, steps.size)

    Row(
        modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(StripBg).border(1.dp, StripLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "Step ${shown + 1} of ${steps.size}: ${step.label}, $statusText" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            when { failed -> Icons.Rounded.Stop; step.active -> Icons.Rounded.Bolt; else -> Icons.Rounded.TaskAlt },
            contentDescription = null, tint = if (failed) StripWarn else StripText, modifier = Modifier.size(16.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(step.label, color = StripText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(statusText, duration).joinToString(" · "), color = if (failed) StripWarn else StripMuted, fontSize = 10.sp, maxLines = 1)
        }
        if (StepPager.showArrows(steps.size)) {
            Icon(Icons.Rounded.ArrowBack, "Previous step", tint = StripMuted,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).clickable { index = StepPager.previous(shown) }.padding(4.dp))
            Text(pagerLabel.orEmpty(), color = StripMuted, fontSize = 10.sp)
            Icon(Icons.Rounded.ChevronRight, "Next step", tint = StripMuted,
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).clickable { index = StepPager.next(shown, steps.size) }.padding(4.dp))
        }
    }
}
