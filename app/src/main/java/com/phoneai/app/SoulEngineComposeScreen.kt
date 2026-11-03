package com.phoneai.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EngineInk = Color(0xFF080808)
private val EnginePanel = Color(0xFF151515)
private val EngineRaised = Color(0xFF202020)
private val EngineLine = Color(0xFF333333)
private val EngineWhite = Color(0xFFF4F5F6)
private val EngineSilver = Color(0xFFBFC5CC)
private val EngineMuted = Color(0xFF9A9DA1)
private val EngineBlue = Color(0xFF7EA9D6)

@Composable
fun SoulEngineComposeScreen(
    status: String,
    metrics: String,
    selfTest: String,
    onRefresh: () -> Unit,
    onSelfTest: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTrading: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapabilityReport: () -> Unit,
    onOpenTeach: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = EngineSilver,
        onPrimary = EngineInk,
        background = EngineInk,
        surface = EnginePanel,
        onSurface = EngineWhite,
        secondary = EngineBlue
    )) {
        Surface(Modifier.fillMaxSize(), color = EngineInk) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.size(43.dp).clip(RoundedCornerShape(14.dp))
                            .background(EnginePanel).border(1.dp, EngineLine, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Hub, null, tint = EngineSilver, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ORION / SOUL ENGINE", color = EngineWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Text("COMMAND CENTER", color = EngineMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Rounded.Settings, "Settings",
                        tint = EngineMuted,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSettings).padding(9.dp).size(21.dp)
                    )
                }

                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(EnginePanel).border(1.dp, EngineLine, RoundedCornerShape(24.dp))
                        .padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("SYSTEM OVERVIEW", color = EngineMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
                            Text(status.substringBefore(" · ").uppercase(), color = if (status.startsWith("READY")) EngineSilver else Color(0xFFE2B97F), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.clip(CircleShape).background(EngineRaised).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("LOCAL STATE", color = EngineMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    NeuralNetworkMark(Modifier.fillMaxWidth().height(124.dp))
                    Text(
                        "One coordination layer. Real tools. Verified outcomes.",
                        color = EngineWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp
                    )
                    Text(
                        "Orion routes requests to chat, phone actions, memory and workflows. Status below comes from the app's local state—not decorative readiness claims.",
                        color = EngineMuted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineButton("Refresh status", Icons.Rounded.Refresh, Modifier.weight(1f), onRefresh)
                        EngineButton("Run self-test", Icons.Rounded.CheckCircle, Modifier.weight(1f), onSelfTest)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("LIVE CAPABILITY SNAPSHOT", "From Orion's local registries and stores")
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(EnginePanel)
                            .border(1.dp, EngineLine, RoundedCornerShape(18.dp)).padding(14.dp)
                    ) {
                        Text(metrics.ifBlank { "Checking local readiness…" }, color = EngineWhite, fontSize = 12.sp, lineHeight = 19.sp)
                        if (selfTest.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text("SELF-TEST", color = EngineMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text(selfTest, color = EngineSilver, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("WORKSPACES", "Open a connected destination")
                    EngineWorkspace("Conversational Copilot", "Ask, reason, create and continue chat history", Icons.Rounded.ChatBubbleOutline, onOpenChat)
                    EngineWorkspace("Tasks & workflows", "Saved tasks, routines and recovery", Icons.Rounded.TaskAlt, onOpenTasks)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineMiniWorkspace("Agents & skills", Icons.Rounded.AutoAwesome, Modifier.weight(1f), onOpenAgents)
                        EngineMiniWorkspace("Memory", Icons.Rounded.Memory, Modifier.weight(1f), onOpenMemory)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineMiniWorkspace("Trading desk", Icons.Rounded.Bolt, Modifier.weight(1f), onOpenTrading)
                        EngineMiniWorkspace("Browser & research", Icons.Rounded.Public, Modifier.weight(1f), onOpenBrowser)
                    }
                    EngineWorkspace("Teach Orion", "Record a correction or reusable workflow", Icons.Rounded.Tune, onOpenTeach)
                    EngineWorkspace("Capability report", "Inspect readiness and evidence", Icons.Rounded.Shield, onOpenCapabilityReport)
                }

                Text(
                    "OPERATING RULE  •  A saved key is not a verified connection. A queued task is not proof of execution. Sensitive actions still require approval.",
                    color = EngineMuted, fontSize = 10.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NeuralNetworkMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val points = listOf(
            Offset(w * .10f, h * .56f), Offset(w * .20f, h * .28f),
            Offset(w * .28f, h * .72f), Offset(w * .38f, h * .43f),
            Offset(w * .48f, h * .18f), Offset(w * .52f, h * .70f),
            Offset(w * .63f, h * .40f), Offset(w * .73f, h * .22f),
            Offset(w * .79f, h * .66f), Offset(w * .91f, h * .45f)
        )
        val links = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 4, 3 to 5, 4 to 6, 5 to 6, 6 to 7, 6 to 8, 7 to 9, 8 to 9, 1 to 4, 2 to 5)
        links.forEach { (a, b) ->
            drawLine(EngineBlue.copy(alpha = .45f), points[a], points[b], strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round)
        }
        points.forEachIndexed { i, point ->
            val radius = if (i == 6) 5.5.dp.toPx() else if (i % 3 == 0) 3.4.dp.toPx() else 2.5.dp.toPx()
            drawCircle(if (i == 6) EngineBlue else EngineSilver, radius, point)
            if (i == 6) drawCircle(EngineBlue.copy(alpha = .16f), radius * 3.1f, point)
        }
        drawLine(EngineLine, Offset(w * .04f, h * .92f), Offset(w * .96f, h * .92f), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = EngineWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(subtitle, color = EngineMuted, fontSize = 11.sp)
    }
}

@Composable
private fun EngineButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(13.dp)).background(EngineRaised)
            .border(1.dp, EngineLine, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = EngineSilver, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = EngineWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EngineWorkspace(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(EnginePanel)
            .border(1.dp, EngineLine, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(EngineRaised), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = EngineSilver, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = EngineWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = EngineMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = EngineMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EngineMiniWorkspace(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(EnginePanel)
            .border(1.dp, EngineLine, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Icon(icon, null, tint = EngineSilver, modifier = Modifier.size(21.dp))
        Text(title, color = EngineWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
