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

private val Ink = Color(0xFF100F18)
private val Panel = Color(0xFF191724)
private val PanelRaised = Color(0xFF211E2E)
private val Mint = Color(0xFFB8A1FF)
private val MintDim = Color(0xFF55418D)
private val Copy = Color(0xFFF4F0FF)
private val Muted = Color(0xFFA6A0B8)
private val Hairline = Color(0xFF383247)

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
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF34284D), Color(0xFF191724))))
                        .border(1.dp, Color(0xFF67558A), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.size(22.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ORION", color = Copy, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                    Text("YOUR PERSONAL AI", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape).background(Color(0xFF211E2E))
                    .border(1.dp, Color(0xFF45365F), CircleShape).padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Mint))
                    Text(status.uppercase().take(14), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Meet your new\nthinking space.", color = Copy, fontSize = 36.sp, lineHeight = 39.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-1.4).sp)
            Text("A calmer place to explore ideas, ask questions and make things happen.", color = Muted,
                fontSize = 13.sp, lineHeight = 19.sp)
        }

        // Full-bleed-feeling orb stage replaces the old stacked promotional card.
        Box(
            Modifier.fillMaxWidth().height(278.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A203A), Color(0xFF191724), Color(0xFF121019))))
                .border(1.dp, Color(0xFF514064), RoundedCornerShape(30.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * .5f
                val cy = size.height * .43f
                drawCircle(Brush.radialGradient(listOf(Color(0x3325E6B1), Color.Transparent), center = Offset(cx, cy), radius = size.width * .58f),
                    radius = size.width * .58f, center = Offset(cx, cy))
                for (i in 0..4) {
                    drawCircle(color = Color(0xFF6FE8C8).copy(alpha = .09f - i * .012f),
                        radius = size.width * (.23f + i * .055f), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 17.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("THE ORION ENGINE", color = Color(0xFFD7CCFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.GraphicEq, null, tint = Mint.copy(alpha = .8f), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                LiquidWaveCore(Modifier.size(154.dp))
                Spacer(Modifier.weight(1f))
                Text("Your mind, with more room.", color = Copy, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Start with a thought. Take it anywhere.", color = Muted, fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WHAT DO YOU NEED TODAY?", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f).height(116.dp).clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFD7CCFF), Color(0xFF9C83E8))))
                        .clickable(onClick = onOpenChat).padding(15.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Ink, modifier = Modifier.size(23.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("AI Chat", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Think out loud", color = Color(0xFF34284D), fontSize = 11.sp)
                    }
                }
                Column(
                    Modifier.weight(1f).height(116.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF211E2E)).border(1.dp, Color(0xFF45365F), RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenVoice).padding(15.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Mint, modifier = Modifier.size(23.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Live voice", color = Copy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Talk hands-free", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR STUDIO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("Explore", color = Mint, fontSize = 11.sp, modifier = Modifier.clickable { onOpenSettings(null) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MiniTile("Tasks", Icons.Rounded.TaskAlt, Modifier.weight(1f), onOpenTasks)
                MiniTile("Browser", Icons.Rounded.Public, Modifier.weight(1f), onOpenBrowser)
                MiniTile("Memory", Icons.Rounded.Memory, Modifier.weight(1f)) { onOpenSettings("memory") }
                MiniTile("Skills", Icons.Rounded.Bolt, Modifier.weight(1f)) { onOpenSettings("skills") }
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF191724))
                .border(1.dp, Hairline, RoundedCornerShape(16.dp)).clickable { onOpenUtilities() }
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF302641)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SmartToy, null, tint = Mint, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Show Orion how you work", color = Copy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Turn your steps into reusable skills", color = Muted, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(19.dp))
        }

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Shield, null, tint = Mint.copy(alpha = .8f), modifier = Modifier.size(17.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SYSTEM STATUS", color = Copy, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(status, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
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
        Text("ORION  /  STUDIO", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Copy, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
        Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF211E2E), Color(0xFF0A1012)))).border(1.dp, Color(0xFF383247).copy(alpha = .8f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Color(0xFF34284D), Color(0xFF211E2E)))).border(1.dp, Color(0xFF67558A).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
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
        modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF131D1F), Color(0xFF0A1012)))).border(1.dp, Color(0xFF35413F).copy(alpha = .7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Color(0xFF34284D), Color(0xFF211E2E)))).border(1.dp, Color(0xFF67558A).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
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
        modifier.clip(RoundedCornerShape(11.dp)).background(Brush.verticalGradient(listOf(Color(0xFF141D1F), Color(0xFF0A1012)))).border(1.dp, Color(0xFF34403F).copy(alpha = .72f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp),
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
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 6.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(Color(0xFF211E2E), Color(0xF0080E10)))).border(1.dp, Color(0xFF34403F).copy(alpha = .8f), RoundedCornerShape(16.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, icon, _) ->
            val active = label == selected
            val activeFill by animateColorAsState(
                targetValue = if (active) Color(0xFF34284D) else Color.Transparent,
                animationSpec = tween(220),
                label = "dock-fill-$label"
            )
            val activeTint by animateColorAsState(
                targetValue = if (active) Mint else Muted,
                animationSpec = tween(180),
                label = "dock-tint-$label"
            )
            Column(
                Modifier.weight(1f).height(47.dp).clip(RoundedCornerShape(10.dp))
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
                    listOf(Color(0xFF514064), Color(0xFF302641), Color(0xFF171522), Color(0xFF292238)),
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
