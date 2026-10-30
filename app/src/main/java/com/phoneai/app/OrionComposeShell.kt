package com.phoneai.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.Hub
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF050505)
private val Panel = Color(0xFF141414)
private val PanelRaised = Color(0xFF202020)
private val Silver = Color(0xFFBFC5CC)
private val GraphiteAccent = Color(0xFF292D32)
private val Copy = Color(0xFFF5F5F5)
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
    onOpenMemory: () -> Unit = {},
    onOpenAgents: () -> Unit = {},
    onOpenSoulEngine: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    onOpenFeature: (String) -> Unit = {}
) {
    var selected by remember { mutableStateOf("Home") }
    val scheme = darkColorScheme(
        primary = Silver,
        onPrimary = Ink,
        background = Ink,
        surface = Panel,
        onSurface = Copy,
        secondary = GraphiteAccent
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(tween(Theme.MOTION_MEDIUM_MS)) +
                            androidx.compose.animation.slideInVertically(tween(Theme.MOTION_SLOW_MS, easing = FastOutSlowInEasing)) { it / 18 }) togetherWith
                            (androidx.compose.animation.fadeOut(tween(Theme.MOTION_FAST_MS)) +
                                androidx.compose.animation.slideOutVertically(tween(Theme.MOTION_FAST_MS)) { -it / 30 })
                    },
                    label = "orion-page"
                ) { page ->
                    when (page) {
                        "Home" -> HomePage(status, onOpenChat, onOpenVoice, onOpenBrowser, onOpenTasks, onOpenUtilities, onOpenSettings)
                        "Tools" -> ToolsPage(onOpenFeature)
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
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1C1C))
                    .border(1.dp, Silver.copy(alpha = .55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Silver, modifier = Modifier.size(23.dp))
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
                val statusLabel = when {
                    status.contains("0 AI provider", true) -> "SET UP AI"
                    status.contains("not enabled", true) -> "CONTROL OFF"
                    else -> "READY"
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (statusLabel == "READY") Silver else Muted))
                Text(statusLabel, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Classic, opaque home surface: no spherical orb, teal glow, or liquid-glass treatment.
        val setupNeeded = status.contains("0 AI provider", true)
        val controlOff = status.contains("not enabled", true)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .border(1.dp, Hairline, RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ORION", color = Copy, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(
                        "PERSONAL AI  /  ${if (setupNeeded) "SETUP NEEDED" else if (controlOff) "PHONE CONTROL OFF" else "READY"}",
                        color = Muted, fontSize = 9.sp, letterSpacing = 1.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(7, 12, 18, 11, 6).forEachIndexed { index, height ->
                        Box(
                            Modifier.width(3.dp).height(height.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (index == 2) Silver else Hairline)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    setupNeeded -> "Connect an AI provider"
                    controlOff -> "Chat is ready. Phone control is off."
                    else -> "Orion is ready"
                },
                color = Copy, fontSize = 21.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(7.dp))
            Text(
                when {
                    setupNeeded -> "Add a provider key in Settings, or configure a supported local model."
                    controlOff -> "Ask questions normally, or enable screen access when you want Orion to operate apps."
                    else -> "Ask a question, talk naturally, or give Orion a task."
                },
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(17.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Silver).clickable(onClick = onOpenVoice).padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Start a live conversation", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                .background(Panel)
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenTasks).padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.TaskAlt, null, tint = Silver, modifier = Modifier.size(22.dp))
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
        Icon(icon, null, tint = Silver, modifier = Modifier.size(25.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Copy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolsPage(onOpenFeature: (String) -> Unit) {
    var showRegisteredTools by remember { mutableStateOf(false) }

    data class Feature(
        val title: String,
        val detail: String,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val route: String
    )
    val groups = listOf(
        "EVERYDAY ASSISTANT" to listOf(
            Feature("Chat with Orion", "The main conversation, commands and image attachments", Icons.Rounded.ChatBubbleOutline, "chat"),
            Feature("Live conversation", "Speak with Orion using the standard voice experience", Icons.Rounded.GraphicEq, "live"),
            Feature("Orion Browser", "Open pages and run assisted research", Icons.Rounded.Public, "browser"),
            Feature("Tasks & workflows", "Saved tasks, reminders and repeatable routines", Icons.Rounded.TaskAlt, "tasks")
        ),
        "WORK, MEMORY & LEARNING" to listOf(
            Feature("Agent workspace", "Trading Coach, Research, Code, Planner and Writer agents", Icons.Rounded.SmartToy, "agents"),
            Feature("Teach mode", "Teach Orion a workflow and review recorded steps", Icons.Rounded.AutoAwesome, "teach"),
            Feature("Memory", "Review, edit, pin and manage remembered information", Icons.Rounded.Memory, "memory"),
            Feature("Knowledge graph", "Explore linked knowledge and saved context", Icons.Rounded.Hub, "knowledge_graph"),
            Feature("Proactive work", "Scheduled briefings and proactive task controls", Icons.Rounded.Bolt, "proactive")
        ),
        "TRADING, GAMES & DEVICE" to listOf(
            Feature("Trading desk", "Market tools, watchlists, journal and analysis", Icons.Rounded.TaskAlt, "trading"),
            Feature("Gaming Coach", "Game-focused coaching and supported device workflows", Icons.Rounded.SmartToy, "gaming"),
            Feature("Device utilities", "Network Radar, Game Sentinel and file tools", Icons.Rounded.Tune, "device_utilities")
        ),
        "SYSTEM, EXTENSIONS & DEVELOPMENT" to listOf(
            Feature("Soul Engine", "System dashboard, readiness and self-tests", Icons.Rounded.Hub, "soul_engine"),
            Feature("Watcher", "Background watcher status and controls", Icons.Rounded.Bolt, "watcher"),
            Feature("Self-extension lab", "Build, sandbox-test and manage reusable extensions", Icons.Rounded.AutoAwesome, "extensions"),
            Feature("Terminal", "Termux bridge and supported terminal workflows", Icons.Rounded.Tune, "terminal"),
            Feature("Workspace files", "Scoped file workspace with allowlisted operations", Icons.Rounded.Memory, "workspace"),
            Feature("Cloud coding agent", "Connected coding-agent workspace", Icons.Rounded.SmartToy, "cloud_coding"),
            Feature("Capability & integrations board", "Connected services, permissions and capability gaps", Icons.Rounded.Public, "capabilities"),
            Feature("Capability report", "Inspect the current capability and readiness report", Icons.Rounded.Shield, "capability_report"),
            Feature("Approval & capabilities", "Approval mode, trusted sessions and tool access", Icons.Rounded.Shield, "agent_control"),
            Feature("Termux bridge", "Optional external terminal runtime with explicit approval", Icons.Rounded.Tune, "termux_bridge"),
            Feature("Owner Control Center", "Private autonomy, recovery, extension and Watcher controls", Icons.Rounded.Settings, "owner_control")
        ),
        "ADVANCED VOICE" to listOf(
            Feature("Gemini Live (experimental)", "Optional live provider mode; uses its own API quota", Icons.Rounded.Mic, "gemini_live")
        )
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        PageHeading("Feature directory", "Every major Orion destination, grouped by what it helps you do.")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                .border(1.dp, Hairline, RoundedCornerShape(14.dp)).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Widgets, null, tint = Silver, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Platform map", color = Copy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${groups.sumOf { it.second.size }} destinations · ${ToolRegistry.all.size} registered tools", color = Muted, fontSize = 11.sp)
            }
            Text("DIRECT", color = Silver, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }

        groups.forEach { (heading, features) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(heading, color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                features.forEach { feature ->
                    ActionRow(feature.title, feature.detail, feature.icon) { onOpenFeature(feature.route) }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
                .border(1.dp, Hairline, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { showRegisteredTools = !showRegisteredTools },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Registered agent tools", color = Copy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${ToolRegistry.all.size} registered contracts · access labels from the live registry", color = Muted, fontSize = 11.sp)
                }
                Text(if (showRegisteredTools) "HIDE" else "VIEW", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (showRegisteredTools) {
                Text("These are tool contracts, not separate app pages. Availability still depends on the current capability, permission and policy state.", color = Muted, fontSize = 11.sp)
                ToolRegistry.all.forEach { definition ->
                    val readOnly = definition.access == ToolAccess.READ
                    val badgeColor = if (readOnly) Silver else Color(0xFFE7BD7B)
                    val badgeBackground = if (readOnly) Color(0xFF242424) else Color(0xFF302820)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(PanelRaised)
                            .border(1.dp, Hairline, RoundedCornerShape(11.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(definition.name, color = Copy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(definition.description, color = Muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            definition.access.name,
                            Modifier.clip(RoundedCornerShape(7.dp)).background(badgeBackground)
                                .border(1.dp, badgeColor.copy(alpha = .45f), RoundedCornerShape(7.dp))
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                            color = badgeColor, fontSize = 8.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(onOpenSettings: (String?) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        PageHeading("Settings", "Choose one settings area at a time. Each shortcut opens its own section.")
        Text("AI & MODELS", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Providers & model selection", "API connections, chat model, vision model and fallback", Icons.Rounded.AutoAwesome) { onOpenSettings("providers") }
        Text("VOICE & INTERACTION", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Voice, wake word & speech", "Microphone, offline speech, neural voice and live-call controls", Icons.Rounded.GraphicEq) { onOpenSettings("voice") }
        ActionRow("Response style & character", "Personality, language and response presentation", Icons.Rounded.SmartToy) { onOpenSettings("character") }
        Text("MEMORY & LEARNING", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Memory & routines", "Review saved memory, routines and learning controls", Icons.Rounded.Memory) { onOpenSettings("memory") }
        ActionRow("Skill Studio", "Manage registered skills and reusable procedures", Icons.Rounded.AutoAwesome) { onOpenSettings("skills") }
        ActionRow("Knowledge base", "Import and manage local reference documents", Icons.Rounded.Hub) { onOpenSettings("knowledge") }
        Text("CONNECTED SERVICES", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Integrations & MCP", "Telegram, MCP servers, connection checks and scopes", Icons.Rounded.Public) { onOpenSettings("integrations") }
        Text("DEVICE & PRIVACY", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Permissions & device access", "Accessibility, microphone, notifications, keyboard and location access", Icons.Rounded.Shield) { onOpenSettings("permissions") }
        ActionRow("Orion health & updates", "Installed version, permission checks, diagnostics and release status", Icons.Rounded.MonitorHeart) { onOpenSettings("health") }
        Text("AUTOMATION & LOCAL TOOLS", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("Screen recording", "Recording controls and saved screen-capture settings", Icons.Rounded.Videocam) { onOpenSettings("recording") }
        ActionRow("Browser & automation", "Browser handoff and assisted navigation settings", Icons.Rounded.Public) { onOpenSettings("browser") }
        ActionRow("Phone tools & diagnostics", "Network Radar, Game Sentinel, files and local device utilities", Icons.Rounded.Tune) { onOpenSettings("tools") }
        Text("SYSTEM", color = Silver, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        ActionRow("All settings & permissions", "Open the complete settings index and advanced configuration", Icons.Rounded.Settings) { onOpenSettings(null) }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ORION  /  STUDIO", color = Silver, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Copy, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
        Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelRaised).border(1.dp, Color(0xFF303030).copy(alpha = .8f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(PanelRaised).border(1.dp, Color(0xFF383838).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Silver, modifier = Modifier.size(21.dp))
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
        modifier.clip(RoundedCornerShape(12.dp)).background(Panel).border(1.dp, Color(0xFF383838).copy(alpha = .7f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(PanelRaised).border(1.dp, Color(0xFF383838).copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Silver, modifier = Modifier.size(20.dp))
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
        modifier.clip(RoundedCornerShape(11.dp)).background(Panel).border(1.dp, Color(0xFF383838).copy(alpha = .72f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, null, tint = Silver, modifier = Modifier.size(21.dp))
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
        // Keep the dock above Android's gesture/navigation area on Android 15+ edge-to-edge windows.
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 13.dp, end = 13.dp, top = 5.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .border(1.dp, Color(0xFF383838), RoundedCornerShape(20.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.take(2).forEach { (label, icon, _) ->
            DockItem(label, icon, selected == label, Modifier.weight(1f)) { onSelect(label) }
        }
        Box(
            Modifier.weight(1.12f).height(58.dp).clip(RoundedCornerShape(16.dp))
                .background(PanelRaised)
                .border(1.5.dp, Silver, RoundedCornerShape(16.dp))
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
    val tint by animateColorAsState(if (active) Silver else Muted, animationSpec = tween(180), label = "dock-item-$label")
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

