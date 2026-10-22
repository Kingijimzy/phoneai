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
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
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

private val Ink = Color(0xFF02080A)
private val Panel = Color(0xFF0A1113)
private val PanelRaised = Color(0xFF10191B)
private val Mint = Color(0xFF00FCB6)
private val MintDim = Color(0xFF016856)
private val Copy = Color(0xFFF1F5F4)
private val Muted = Color(0xFFA0AEAD)
private val Hairline = Color(0xFF243230)

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
            .padding(start = 19.dp, end = 19.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ORION", color = Copy, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.6.sp)
                Spacer(Modifier.height(5.dp))
                Text("PERSONAL AI", color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
            }
            Row(
                Modifier.clip(CircleShape).background(Color(0xFF12231F)).border(1.dp, Color(0xFF315B51), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Mint))
                Spacer(Modifier.width(7.dp))
                Text(status.uppercase().take(12), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("A smarter way\nto get things done.", color = Copy, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.9).sp)
            Text("A calm space to think, speak and get things done.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }

        // Orion's focal surface: compact glass shell with a deliberately sculpted liquid orb.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xE51B292A), Color(0xD50D1719), Color(0xF2080E10))))
                .border(1.dp, Color(0xFF52635F).copy(alpha = .52f), RoundedCornerShape(18.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ORION CORE", color = Color(0xFFC6D7D2), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    Text("Ready when you are", color = Muted, fontSize = 11.sp)
                }
                Row(
                    Modifier.clip(CircleShape)
                        .background(Color(0xFF11241F).copy(alpha = .9f))
                        .border(1.dp, Color(0xFF38665A).copy(alpha = .6f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Mint))
                    Spacer(Modifier.width(6.dp))
                    Text(status.uppercase().take(10), color = Color(0xFFB9EDE0), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                LiquidWaveCore(Modifier.size(116.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFB4F6E5), Color(0xFF65D9BE))))
                        .clickable(onClick = onOpenVoice).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Mic, null, tint = Ink, modifier = Modifier.size(16.dp))
                        Text("Talk to Orion", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF1B292B), Color(0xFF11191B))))
                        .border(1.dp, Color(0xFF465653).copy(alpha = .72f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenChat).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Copy, modifier = Modifier.size(16.dp))
                        Text("Open chat", color = Copy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("QUICK ACCESS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Spacer(Modifier.weight(1f))
            Text("Private by default", color = Muted, fontSize = 10.sp)
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Brush.verticalGradient(listOf(Color(0xFF101719), Color(0xFF0A1012)))).border(1.dp, Color(0xFF273130), RoundedCornerShape(13.dp)).padding(13.dp)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF11191B), Color(0xFF0A1012)))).border(1.dp, Color(0xFF273231).copy(alpha = .8f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF142421)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Copy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = "Open $title", tint = Muted, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0C1315)).border(1.dp, Color(0xFF1D2A2B), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF142421)), contentAlignment = Alignment.Center) {
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
        modifier.clip(RoundedCornerShape(11.dp)).background(Brush.verticalGradient(listOf(Color(0xFF11191B), Color(0xFF0A1012)))).border(1.dp, Color(0xFF273231).copy(alpha = .8f), RoundedCornerShape(11.dp))
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
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B1214)).border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, icon, _) ->
            val active = label == selected
            val activeFill by animateColorAsState(
                targetValue = if (active) Color(0xFF1D3731) else Color.Transparent,
                animationSpec = tween(220),
                label = "dock-fill-$label"
            )
            val activeTint by animateColorAsState(
                targetValue = if (active) Mint else Muted,
                animationSpec = tween(180),
                label = "dock-tint-$label"
            )
            Column(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp))
                    .background(activeFill)
                    .clickable { onSelect(label) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = activeTint, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, color = activeTint, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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
    val transition = rememberInfiniteTransition(label = "liquid-glass-orb")
    val time by transition.animateFloat(
        initialValue = 0f, targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(6200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "orb-time"
    )
    var drag by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    val pulseValue by animateFloatAsState(targetValue = pulse, animationSpec = tween(360), label = "orb-touch")
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
        }.clickable { pulse = if (pulse > .5f) 0f else 1f },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = minOf(w, h) * .43f
            // Soft atmospheric bloom, kept deliberately dim for a premium rather than neon look.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0x5538DCC0), Color(0x2224A895), Color.Transparent),
                    Offset(cx, cy), radius * 1.62f
                ), radius * 1.62f
            )
            // Glass body: cool graphite edge, deep translucent teal core and a subtle lit rim.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF25413F), Color(0xFF132426), Color(0xFF081114), Color(0xFF172626)),
                    Offset(cx - radius * .28f, cy - radius * .34f), radius * 1.55f
                ), radius
            )
            drawCircle(
                Brush.linearGradient(
                    listOf(Color(0x22FFFFFF), Color(0x05FFFFFF), Color(0x0010181A)),
                    start = Offset(cx - radius, cy - radius),
                    end = Offset(cx + radius, cy + radius)
                ), radius * .965f
            )
            val clipPath = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(cx - radius * .96f, cy - radius * .96f, cx + radius * .96f, cy + radius * .96f))
            }
            val waves = listOf(
                Triple(.06f, 1.00f, .74f),
                Triple(.10f, 1.17f, .43f),
                Triple(.13f, .83f, .27f),
                Triple(.16f, 1.31f, .17f)
            )
            for ((layer, wave) in waves.withIndex()) {
                val path = Path()
                val amplitude = radius * (wave.first + pulseValue * .045f)
                for (i in 0..100) {
                    val nx = i / 100f
                    val x = cx - radius + 2f * radius * nx
                    val envelope = (.18f + .82f * sin(PI.toFloat() * nx))
                    val y = cy + radius * (.10f + layer * .055f) +
                        sin(nx * 2f * PI.toFloat() * wave.second + time * (1f + layer * .12f) + drag) * amplitude * envelope
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawContext.canvas.save()
                drawContext.canvas.clipPath(clipPath)
                drawPath(
                    path,
                    Brush.horizontalGradient(listOf(Color(0x6639BBA7), Mint.copy(alpha = wave.third), Color(0x6642CBB4))),
                    style = Stroke(width = if (layer == 0) 2.2.dp.toPx() else 1.15.dp.toPx(), cap = StrokeCap.Round)
                )
                if (layer == 0) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(cx + radius, cy + radius)
                        lineTo(cx - radius, cy + radius)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(Color(0x333CE1C5), Color(0x0A08A997)), startY = cy, endY = cy + radius))
                }
                drawContext.canvas.restore()
            }
            // Fine glass rim and a broken inner reflection.
            drawCircle(Color(0x887DA9A2), radius, style = Stroke(width = .85.dp.toPx()))
            drawArc(
                color = Color(0x88FFFFFF), startAngle = 208f, sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(cx - radius * .91f, cy - radius * .91f),
                size = androidx.compose.ui.geometry.Size(radius * 1.82f, radius * 1.82f),
                style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round)
            )
            // Small specular reflection gives the orb a glass surface without a costly blur.
            drawCircle(
                Brush.radialGradient(listOf(Color(0x66FFFFFF), Color(0x12FFFFFF), Color.Transparent), Offset(cx - radius * .38f, cy - radius * .48f), radius * .42f),
                radius * .42f, Offset(cx - radius * .38f, cy - radius * .48f)
            )
            val dotX = cx + sin(time + drag) * radius * .57f
            val dotY = cy + radius * .10f + sin(time * 1.3f + drag) * radius * .12f
            drawCircle(Color(0xAA8FFFF0), radius * .06f, Offset(dotX, dotY))
            drawCircle(Color.White.copy(alpha = .9f), radius * .022f, Offset(dotX, dotY))
        }
    }
}
