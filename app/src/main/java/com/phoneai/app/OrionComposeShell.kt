package com.phoneai.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val Ink = Color(0xFF070B0D)
private val Panel = Color(0xFF101719)
private val PanelRaised = Color(0xFF151F21)
private val Mint = Color(0xFF72E3CE)
private val MintDim = Color(0xFF2F8278)
private val Copy = Color(0xFFF0F5F5)
private val Muted = Color(0xFF9BAAAC)
private val Hairline = Color(0xFF263638)

@Composable
fun OrionComposeShell(
    status: String,
    onOpenChat: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenUtilities: () -> Unit
) {
    var selected by remember { mutableStateOf("Home") }
    val scheme = darkColorScheme(
        primary = Mint,
        onPrimary = Ink,
        background = Ink,
        surface = Panel,
        onSurface = Copy,
        secondary = MintDim
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(targetState = selected, label = "orion-page") { page ->
                    when (page) {
                        "Home" -> HomePage(status, onOpenChat, onOpenVoice, onOpenBrowser, onOpenTasks, onOpenUtilities, onOpenSettings)
                        "Tools" -> ToolsPage(onOpenBrowser, onOpenTasks, onOpenUtilities, onOpenSettings)
                        else -> SettingsPage(onOpenSettings)
                    }
                }
                BottomDock(selected) { next ->
                    if (next == "Chat") onOpenChat() else selected = next
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    status: String,
    onOpenChat: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenUtilities: () -> Unit,
    onOpenSettings: (String?) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("O R I O N", color = Copy, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.height(5.dp))
                Text("PERSONAL INTELLIGENCE", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp)
            }
            Row(
                Modifier.clip(CircleShape).background(Color(0xFF12231F)).border(1.dp, Color(0xFF315B51), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Mint))
                Spacer(Modifier.width(7.dp))
                Text("READY", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Your world,\nwithin reach.", color = Copy, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
            Text("Think it through. Say it out loud. Let Orion handle the steps.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }

        // Compact voice waveform replaces the oversized orb: a calmer, assistant-first workspace.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF14201F), Color(0xFF0D1315))))
                .border(1.dp, Color(0xFF2B403D), RoundedCornerShape(26.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ORION IS READY", color = Copy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp)
                    Text("Talk, think, or ask for help.", color = Muted, fontSize = 12.sp)
                }
                Box(
                    Modifier.clip(CircleShape).background(Color(0xFF1C302D))
                        .border(1.dp, Color(0xFF315B51), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Mint))
                        Text(status.uppercase().take(18), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                    }
                }
            }
            VoiceWaveform(Modifier.fillMaxWidth().height(66.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Mint)
                        .clickable(onClick = onOpenVoice).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Mic, null, tint = Ink, modifier = Modifier.size(17.dp))
                        Text("Start voice", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF192322))
                        .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                        .clickable(onClick = onOpenChat).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Copy, modifier = Modifier.size(17.dp))
                        Text("Text chat", color = Copy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Button(
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
        ) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Start a conversation", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("↗", fontSize = 20.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile("Live voice", "Speak naturally", Icons.Rounded.GraphicEq, Modifier.weight(1f), onOpenVoice)
            ActionTile("Orion browser", "Browse with intent", Icons.Rounded.Public, Modifier.weight(1f), onOpenBrowser)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR WORKSPACE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("VIEW ALL  →", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpenUtilities() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("Task centre", Icons.Rounded.TaskAlt, Modifier.weight(1f), onOpenTasks)
            MiniTile("Memory", Icons.Rounded.Memory, Modifier.weight(1f)) { onOpenSettings("memory") }
            MiniTile("Skills", Icons.Rounded.Bolt, Modifier.weight(1f)) { onOpenSettings("skills") }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(20.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = Mint, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text("SYSTEM STATUS", color = Copy, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(status, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ToolsPage(
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenUtilities: () -> Unit,
    onOpenSettings: (String?) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeading("Tools & skills", "Your capabilities, one clear workspace.")
        ActionRow("Orion Browser", "Web navigation and assisted research", Icons.Rounded.Public, onOpenBrowser)
        ActionRow("Task centre", "Saved tasks and routines", Icons.Rounded.TaskAlt, onOpenTasks)
        ActionRow("Teach mode", "Record a workflow for Orion to repeat", Icons.Rounded.SmartToy, onOpenUtilities)
        ActionRow("AI providers", "Models, API connections and selection", Icons.Rounded.AutoAwesome) { onOpenSettings("providers") }
        ActionRow("Voice & interaction", "Voice controls and assistant behavior", Icons.Rounded.GraphicEq) { onOpenSettings("voice") }
    }
}

@Composable
private fun SettingsPage(onOpenSettings: (String?) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeading("Settings", "Shape how Orion works with you.")
        ActionRow("AI providers", "Manage Gemini, NVIDIA and other models", Icons.Rounded.AutoAwesome) { onOpenSettings("providers") }
        ActionRow("Voice & character", "Speech, voice and interaction modes", Icons.Rounded.GraphicEq) { onOpenSettings("voice") }
        ActionRow("Memory & routines", "Review stored context and routines", Icons.Rounded.Memory) { onOpenSettings("memory") }
        ActionRow("Integrations", "Connect supported services", Icons.Rounded.Tune) { onOpenSettings("integrations") }
        ActionRow("All settings", "Permissions, privacy and app options", Icons.Rounded.Settings) { onOpenSettings(null) }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ORION / WORKSPACE", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Copy, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
        Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A302D)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Copy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text("›", color = Muted, fontSize = 27.sp)
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(21.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(21.dp))
            .clickable(onClick = onClick).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1B302D)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Copy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun MiniTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(PanelRaised).border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, null, tint = Mint, modifier = Modifier.size(21.dp))
        Text(title, color = Copy, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BottomDock(selected: String, onSelect: (String) -> Unit) {
    val items = listOf(
        Triple("Home", Icons.Rounded.Home, "Home"),
        Triple("Chat", Icons.Rounded.ChatBubbleOutline, "Chat"),
        Triple("Tools", Icons.Rounded.Widgets, "Tools"),
        Triple("Settings", Icons.Rounded.Settings, "Settings")
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF101819)).border(1.dp, Hairline, RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, icon, _) ->
            val active = label == selected
            Column(
                Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(17.dp))
                    .background(if (active) Brush.linearGradient(listOf(Color(0xFF24413A), Color(0xFF142321))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(label) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = if (active) Mint else Muted, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = if (active) Mint else Muted, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun VoiceWaveform(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voice-waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "voice-wave-phase"
    )
    Canvas(modifier) {
        val count = 43
        val gap = size.width / (count * 1.7f)
        val barWidth = gap * .7f
        val centerY = size.height / 2f
        for (i in 0 until count) {
            val x = i * gap + gap * .5f
            val envelope = sin((i.toFloat() / (count - 1) * PI).toFloat()).toFloat()
            val wave = (sin(phase + i * .47f) * .5f + .5f)
            val h = (8f + envelope * (12f + wave * 27f)).coerceAtMost(size.height * .92f)
            drawLine(
                color = if (i in 19..23) Mint else Mint.copy(alpha = .35f + envelope * .55f),
                start = Offset(x, centerY - h / 2f),
                end = Offset(x, centerY + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun LiquidWaveCore(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "liquid-wave")
    val time by transition.animateFloat(
        initialValue = 0f, targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "wave-time"
    )
    var drag by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    val pulseValue by animateFloatAsState(targetValue = pulse, animationSpec = tween(320), label = "wave-pulse")
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pulse = 1f; drag += .35f },
                onDragEnd = { pulse = 0f },
                onDragCancel = { pulse = 0f },
                onDrag = { change, amount ->
                    change.consume()
                    drag = (drag + amount.x / size.width * 1.4f).let { ((it % 12f) + 12f) % 12f }
                    pulse = (pulse + amount.y / size.height).coerceIn(0f, 1f)
                }
            )
        }.clickable { pulse = 1f - pulse },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = minOf(w, h) * .44f
            drawCircle(Brush.radialGradient(listOf(Color(0xAA2BC7AE), Color(0x4421A895), Color.Transparent), Offset(cx, cy), radius * 1.5f), radius * 1.5f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF1A3433), Color(0xFF0A171A), Color(0xFF071012)), Offset(cx - radius * .25f, cy - radius * .30f), radius * 1.35f), radius)
            val clipPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - radius * .96f, cy - radius * .96f, cx + radius * .96f, cy + radius * .96f)) }
            val waveColor = Mint
            for (layer in 0..3) {
                val path = Path()
                val amplitude = radius * (.10f + layer * .025f + pulseValue * .08f)
                for (i in 0..90) {
                    val nx = i / 90f
                    val x = cx - radius + 2f * radius * nx
                    val envelope = (.3f + .7f * sin(PI.toFloat() * nx))
                    val y = cy + radius * (.12f + layer * .055f) +
                        sin(nx * 2f * PI.toFloat() + time * (1f + layer * .13f) + drag) * amplitude * envelope
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(clipPath)
                drawPath(path, waveColor.copy(alpha = .82f - layer * .15f), style = Stroke(width = if (layer == 0) 2.7.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round))
                if (layer == 0) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(cx + radius, cy + radius)
                        lineTo(cx - radius, cy + radius)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(Color(0x443EE0C7), Color(0x0807A997)), startY = cy, endY = cy + radius))
                }
                drawContext.canvas.restore()
            }
            drawCircle(Color(0x663FE5CF), radius, style = Stroke(width = 1.1.dp.toPx()))
            val dotX = cx + sin(time + drag) * radius * .62f
            val dotY = cy + radius * .12f + sin(time * 1.3f + drag) * radius * .10f
            drawCircle(Color.White.copy(alpha = .85f), radius * .035f, Offset(dotX, dotY))
        }
    }
}
