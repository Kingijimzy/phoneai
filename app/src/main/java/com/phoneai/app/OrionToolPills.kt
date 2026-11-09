package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * nanoMuse-style tool timeline: each tool step of the running task is a small rounded pill with a status icon
 * (green terminal = done, red = failed, spinner = running), its title and how long it took.
 */
@Composable
internal fun OrionToolPills(lines: List<OrionChatLine>, modifier: Modifier = Modifier) {
    val stepLines = remember(lines.size, lines.lastOrNull()?.text) { lines.map { StepLine(it.id, it.kind, it.text) } }
    val steps = remember(stepLines) { StepTracker.currentTaskSteps(stepLines) }
    if (steps.isEmpty()) return
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    val anyActive = steps.any { it.active }
    LaunchedEffect(anyActive) { while (anyActive) { now = System.nanoTime(); delay(1000) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.takeLast(12).forEach { step ->
            val failed = step.status == StepStatus.FAILED || step.status == StepStatus.CANCELLED
            Row(
                Modifier.orionEnter(18f).widthIn(max = 360.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C1E))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                when {
                    step.active -> CircularProgressIndicator(Modifier.size(15.dp), color = Color(0xFF7EE787), strokeWidth = 2.dp)
                    failed -> Icon(Icons.Rounded.Close, null, tint = Color(0xFFFF6B5E), modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Rounded.Terminal, null, tint = Color(0xFF7EE787), modifier = Modifier.size(16.dp))
                }
                Text(step.label, color = Color(0xFFF2F2F2), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                step.durationLabel(now)?.let { Text(it, color = Color(0xFF8A8F98), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
