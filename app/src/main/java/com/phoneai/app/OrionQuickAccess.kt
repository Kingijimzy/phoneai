package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * One map of the platform: every page and chat action Orion has, with an icon, in a few groups.
 * The quick bar and the "Everything" sheet both read from here, so nothing is reachable from one place and missing from the other.
 */
internal object OrionQuickAccess {
    data class Tile(val id: String, val title: String, val icon: ImageVector, val page: String? = null, val action: String? = null)
    data class Group(val title: String, val tiles: List<Tile>)

    val groups: List<Group> = listOf(
        Group("WORK", listOf(
            Tile("studio", "Code Studio", Icons.Rounded.Code, page = "studio"),
            Tile("terminal", "Terminal", Icons.Rounded.Terminal, page = "terminal"),
            Tile("files", "Files", Icons.Rounded.FolderOpen, page = "workspace"),
            Tile("browser", "Browser", Icons.Rounded.Public, page = "browser"),
            Tile("tasks", "Tasks", Icons.Rounded.TaskAlt, page = "tasks"),
            Tile("agents", "Specialists", Icons.Rounded.Groups, page = "agents")
        )),
        Group("MIND", listOf(
            Tile("memory", "Memory", Icons.Rounded.Psychology, page = "memory"),
            Tile("knowledge", "Knowledge", Icons.Rounded.AccountTree, page = "knowledge"),
            Tile("soul", "Soul", Icons.Rounded.AutoAwesome, page = "soul"),
            Tile("proactive", "Proactive", Icons.Rounded.Bolt, page = "proactive")
        )),
        Group("CONNECT", listOf(
            Tile("accounts", "Accounts", Icons.Rounded.Hub, page = "accounts"),
            Tile("telegram", "Telegram", Icons.Rounded.Send, page = "telegram"),
            Tile("mcp", "MCP", Icons.Rounded.Cable, page = "mcp"),
            Tile("extensions", "Extensions", Icons.Rounded.Extension, page = "extensions"),
            Tile("capabilities", "Tools", Icons.Rounded.Tune, page = "capabilities"),
            Tile("voice", "Voice", Icons.Rounded.RecordVoiceOver, page = "voice"),
            Tile("trading", "Trading", Icons.Rounded.TrendingUp, page = "trading")
        )),
        Group("CHAT", listOf(
            Tile("history", "History", Icons.Rounded.History, action = "history"),
            Tile("attach", "Chat files", Icons.Rounded.AttachFile, action = "chat_files"),
            Tile("skills", "Skills", Icons.Rounded.SmartToy, action = "skills"),
            Tile("mcps", "MCPs in chat", Icons.Rounded.Cable, action = "mcps"),
            Tile("memories", "Memories in chat", Icons.Rounded.Memory, action = "memories"),
            Tile("usage", "Usage", Icons.Rounded.DataUsage, action = "token_usage"),
            Tile("alerts", "Alerts", Icons.Rounded.Notifications, action = "alerts"),
            Tile("notify", "Notifications", Icons.Rounded.Notifications, page = "notifications"),
            Tile("feedback", "Feedback", Icons.Rounded.Feedback, action = "feedback")
        )),
        Group("SYSTEM", listOf(
            Tile("models", "Models", Icons.Rounded.Insights, page = "models"),
            Tile("settings", "Settings", Icons.Rounded.Settings, page = "settings"),
            Tile("control", "Control", Icons.Rounded.Verified, page = "control"),
            Tile("report", "Report", Icons.Rounded.Insights, page = "report")
        ))
    )

    /** The six places used all the time, one tap from the composer. */
    val bar: List<Tile> = listOf("studio", "terminal", "files", "browser", "tasks", "memory").mapNotNull { id ->
        groups.flatMap { it.tiles }.firstOrNull { it.id == id }
    }

    fun all(): List<Tile> = groups.flatMap { it.tiles }
}

@Composable
internal fun OrionQuickBar(onEverything: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        (OrionQuickAccess.bar + OrionQuickAccess.Tile("all", "Everything", Icons.Rounded.Apps)).forEach { t ->
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, CircleShape)
                    .semantics { contentDescription = t.title; role = Role.Button }
                    .clickable(role = Role.Button) { if (t.page != null) OrionPages.open(c, t.page) else onEverything() },
                contentAlignment = Alignment.Center
            ) { Icon(t.icon, null, tint = OrionUiPalette.secondaryText, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Every page and chat action as an icon grid, grouped; tap to go. */
@Composable
internal fun OrionEverythingSheet(onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val c = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(18.dp)).background(OrionUiPalette.panel)
            .border(1.dp, OrionUiPalette.border, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OrionQuickAccess.groups.forEach { g ->
                Text(g.title, color = OrionUiPalette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                g.tiles.chunked(4).forEach { rowTiles ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTiles.forEach { t ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(OrionUiPalette.raised)
                                    .semantics { contentDescription = t.title; role = Role.Button }
                                    .clickable(role = Role.Button) {
                                        onDismiss()
                                        if (t.page != null) OrionPages.open(c, t.page) else t.action?.let(onAction)
                                    }.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(t.icon, null, tint = OrionUiPalette.text, modifier = Modifier.size(22.dp))
                                Text(t.title, color = OrionUiPalette.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(4 - rowTiles.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
