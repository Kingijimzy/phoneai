package com.phoneai.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Raycast-grade access: typing "/" in the composer lists every page; tap to jump there. */
@Composable
internal fun OrionSlashPalette(draft: String, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val active = draft.startsWith("/")
    AnimatedVisibility(active, enter = fadeIn(tween(120)) + expandVertically(tween(150)), exit = fadeOut(tween(100)) + shrinkVertically(tween(100))) {
        val hits = OrionPages.search(draft.drop(1)).filter { it.id != "chat" }.take(6)
        Column(
            modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(OrionUiPalette.panel).border(1.dp, OrionUiPalette.border, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)
        ) {
            if (hits.isEmpty()) Text("No page matches", color = OrionUiPalette.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            hits.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { OrionPages.open(context, p.id); onPick() }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = OrionUiPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(p.about, color = OrionUiPalette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("↗", color = OrionUiPalette.muted, fontSize = 12.sp)
                }
            }
        }
    }
}
