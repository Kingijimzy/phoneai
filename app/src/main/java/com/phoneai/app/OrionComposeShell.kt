package com.phoneai.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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

private val Ink = Color(0xFF050505)
private val Panel = Color(0xFF141414)
private val PanelRaised = Color(0xFF202020)
private val Mint = Color(0xFF72E6C1)
private val MintDim = Color(0xFF17483B)
private val Copy = Color(0xFFF7FAF9)
private val Muted = Color(0xFF9B9B9B)
private val Hairline = Color(0xFF303030)

@Composable
fun OrionComposeShell(
    status: String,
    onOpenChat: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenUtilities: () -> Unit,
    onOpenTrading: () -> Unit = {},
    onOpenMemory: () -> Unit = {}
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
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(tween(260)) +
                            androidx.compose.animation.slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 18 }) togetherWith
                            (androidx.compose.animation.fadeOut(tween(130)) +
                                androidx.compose.animation.slideOutVertically(tween(180)) { -it / 30 })
                    },
                    label = "orion-page"
                ) { page ->
                    when (page) {
                        "Home" -> HomePage(status, onOpenChat, onOpenVoice, onOpenBrowser, onOpenTasks, onOpenUtilities, onOpenSettings)
                        "Tools" -> ToolsPage(onOpenBrowser, onOpenTasks, onOpenUtilities, onOpenSettings, onOpenTrading, onOpenMemory)
                        else -> SettingsPage(onOpenSettings)
                    }
                }
                BottomDock(selected) { next ->
                    when (next) { "Chat" -> onOpenChat(); "Live" -> onOpenVoice(); else -> selected = next }
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
    val pulse = rememberInfiniteTransition(label = "orion-ready-pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.18f, targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ready-glow"
    )
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1C))
                    .border(1.dp, Mint.copy(alpha = .55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("ORION", color = Copy, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("PERSONAL AI ASSISTANT", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(Panel).border(1.dp, Hairline, CircleShape)
                    .clickable { onOpenSettings(null) }.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Mint.copy(alpha = (.65f + glow).coerceIn(0f, 1f))))
                Text(status.uppercase().take(13), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Live assistant core: compact, functional, and deliberately not a promotional splash screen.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(27.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Mint.copy(alpha = .25f + glow * .25f), RoundedCornerShape(27.dp))
                .padding(horizontal = 19.dp, vertical = 17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ORION CORE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.GraphicEq, null, tint = Mint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(184.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0..3) {
                        drawCircle(
                            color = Mint.copy(alpha = (0.055f + glow * .035f) - i * .008f),
                            radius = size.width * (.28f + i * .095f),
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                    drawCircle(
                        brush = Brush.radialGradient(listOf(Color(0xFF202020), Color(0xFF101010), Color(0xFF050505))),
                        radius = size.width * .36f, center = center
                    )
                    drawCircle(color = Mint.copy(alpha = .55f), radius = size.width * .36f, center = center, style = Stroke(width = 1.2.dp.toPx()))
                }
                LiquidWaveCore(Modifier.size(126.dp))
            }
            Text("Orion is ready", color = Copy, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Mint))
                Text("Assistant standing by", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(17.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Mint).clickable(onClick = onOpenVoice).padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Start a live conversation", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text("What would you like to do?", color = Copy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        // Focus the home surface: one live action, two distinct destinations, and one workflow entry.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeActionCard("Chat with Orion", "Ask, reason, create", Icons.Rounded.ChatBubbleOutline, Modifier.weight(1f), onOpenChat)
            HomeActionCard("Explore the web", "Research when you ask", Icons.Rounded.Public, Modifier.weight(1f), onOpenBrowser)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF191919), Color(0xFF101010))))
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenTasks).padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.TaskAlt, null, tint = Mint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Tasks & workflows", color = Copy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Continue a saved task or teach Orion a routine", color = Muted, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.height(133.dp).clip(RoundedCornerShape(21.dp)).background(Color(0xFF141414))
            .border(1.dp, Color(0xFF383838), RoundedCornerShape(21.dp))
            .clickable(onClick = onClick).padding(15.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, null, tint = Mint, modifier = Modifier.size(25.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Copy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolsPage(
    onOpenBrowser: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenUtilities: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenTrading: () -> Unit,
    onOpenMemory: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeading("Tools & skills", "Your capabilities, one clear workspace.")
        ActionRow("Orion Browser", "Web navigation and assisted research", Icons.Rounded.Public, onOpenBrowser)
        ActionRow("Task centre", "Saved tasks and routines", Icons.Rounded.TaskAlt, onOpenTasks)
        ActionRow("Trading desk", "Bybit market scanner, journal and watchlist", Icons.Rounded.TaskAlt, onOpenTrading)
        ActionRow("Memory", "What Orion remembers; pin, edit or delete", Icons.Rounded.Memory, onOpenMemory)
        ActionRow("Teach mode", "Record a workflow for Orion to repeat", Icons.Rounded.SmartToy, onOpenUtilities)
        ActionRow("AI providers", "Models, API connections and selection", Icons.Rounded.AutoAwesome) { onOpenSettings("providers") }
        ActionRow("Voice & interaction", "Voice controls and assistant behavior", Icons.Rounded.GraphicEq) { onOpenSettings("voice") }

        Spacer(Modifier.height(4.dp))
        Text("AVAILABLE AGENT TOOLS", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Text(
            "Access labels come from the same registry that builds Orion's tool prompt and scope guard.",
            color = Muted,
            fontSize = 12.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ToolRegistry.all.forEach { definition ->
                val readOnly = definition.access == ToolAccess.READ
                val badgeColor = if (readOnly) Mint else Color(0xFFE7BD7B)
                val badgeBackground = if (readOnly) Color(0xFF172D28) else Color(0xFF302820)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, Hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(definition.name, color = Copy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            definition.description,
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        definition.access.name,
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(badgeBackground)
                            .border(1.dp, badgeColor.copy(alpha = .45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF202020), Color(0xFF101010)))).border(1.dp, Color(0xFF303030).copy(alpha = .8f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Color(0xFF303030), Color(0xFF202020)))).border(1.dp, Color(0xFF383838).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
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
        modifier.clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF181818), Color(0xFF101010)))).border(1.dp, Color(0xFF383838).copy(alpha = .7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(Color(0xFF303030), Color(0xFF202020)))).border(1.dp, Color(0xFF383838).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
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
        modifier.clip(RoundedCornerShape(11.dp)).background(Brush.verticalGradient(listOf(Color(0xFF181818), Color(0xFF101010)))).border(1.dp, Color(0xFF383838).copy(alpha = .72f), RoundedCornerShape(11.dp))
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
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 5.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF141414), Color(0xFF101010))))
            .border(1.dp, Color(0xFF383838), RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.take(2).forEach { (label, icon, _) ->
            DockItem(label, icon, selected == label, Modifier.weight(1f)) { onSelect(label) }
        }
        Box(
            Modifier.weight(1.12f).height(58.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF454545), Color(0xFF242424), Color(0xFF171717))))
                .border(1.5.dp, Mint.copy(alpha = .85f), CircleShape)
                .clickable { onSelect("Live") },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(25.dp))
                Text("LIVE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        items.drop(2).forEach { (label, icon, _) ->
            DockItem(label, icon, selected == label, Modifier.weight(1f)) { onSelect(label) }
        }
    }
}

@Composable
private fun DockItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) Mint else Muted, animationSpec = tween(180), label = "dock-item-$label")
    Column(
        modifier.height(49.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0xFF252525) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 9.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
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
    val transition = rememberInfiniteTransition(label = "orion-core")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "core-phase"
    )
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) * .43f
        drawCircle(Color(0xFF181818), radius, Offset(cx, cy))
        drawCircle(Color(0xFF383838), radius, Offset(cx, cy), style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(Color(0xFF252525), radius * .78f, Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        val path = Path()
        for (i in 0..80) {
            val xRatio = i / 80f
            val x = cx - radius * .78f + radius * 1.56f * xRatio
            val envelope = sin(PI.toFloat() * xRatio)
            val y = cy + sin(xRatio * PI.toFloat() * 4f + phase) * radius * .12f * envelope
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFFD8D8D8), style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Color(0xFFE8E8E8), radius * .035f, Offset(cx + sin(phase) * radius * .42f, cy))
    }
}
