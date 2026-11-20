package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything Orion is doing right now, from the one coordinator's own state: loop stage, missions, workers, latest steps. */
internal object OrionLiveView {
    data class Snapshot(
        val surface: String,
        val stages: List<Pair<String, Boolean>>,
        val missions: List<String>,
        val workers: List<String>,
        val steps: List<String>,
        val busy: Boolean
    ) {
        val empty: Boolean get() = !busy && missions.isEmpty() && workers.isEmpty()
    }

    /** Pure so it can be tested: no Android, no clock. */
    internal fun build(
        live: OrionEntityLoop.Live?,
        activeMissions: List<OrionMissionControl.Mission>,
        workers: List<OrionWorkers.Job>,
        steps: List<OrionRunLog.Step>,
        now: Long
    ): Snapshot {
        val busy = live != null && live.endedAt == 0L
        val reached = live?.reached.orEmpty()
        val stages = OrionEntityLoop.Stage.values().map { it.name.lowercase() to (it in reached) }
        fun secs(from: Long) = ((now - from).coerceAtLeast(0L) / 1000)
        return Snapshot(
            surface = live?.surface.orEmpty(),
            stages = stages,
            missions = activeMissions.take(4).map { "${it.lifecycle.phase.name.lowercase()} · ${it.request.take(48)} · ${secs(it.startedAt)}s" },
            workers = workers.take(3).map { "${it.label.take(32)} · ${it.steps} steps · ${secs(it.startedAt)}s" },
            steps = steps.takeLast(4).map { (if (it.status == OrionRunLog.Status.FAILED) "failed · " else if (it.status == OrionRunLog.Status.RUNNING) "now · " else "done · ") + it.title.take(56) },
            busy = busy
        )
    }

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot =
        build(OrionEntityLoop.live, OrionMissionControl.active(), OrionWorkers.running(), OrionRunLog.currentRun(), now)
}

/** A card that updates in place while Orion works, so everything it does can be watched without opening another page. */
@Composable
internal fun OrionLiveRunCard(modifier: Modifier = Modifier) {
    var snap by remember { mutableStateOf(OrionLiveView.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = OrionLiveView.snapshot()
            kotlinx.coroutines.delay(if (snap.empty) 1500L else 600L)
        }
    }
    if (snap.empty) return
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(if (snap.busy) "LIVE · ${snap.surface.uppercase()}" else "LAST RUN", color = OrionUiPalette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            snap.stages.forEach { (name, reached) ->
                Text(name, color = if (reached) OrionUiPalette.success else OrionUiPalette.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
        snap.steps.forEach { Text(it, color = OrionUiPalette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        snap.missions.forEach { Text("mission · $it", color = OrionUiPalette.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        snap.workers.forEach { Text("worker · $it", color = OrionUiPalette.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
