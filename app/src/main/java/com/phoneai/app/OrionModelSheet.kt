package com.phoneai.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "Chat Model" sheet: a Smart Mode card (automatic model routing) over one row per provider, tagged READY or ADD KEY. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrionModelSheet(onDismiss: () -> Unit, onNeedKey: () -> Unit, onChanged: () -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val current = remember(tick) { Prefs.provider(c, false).id }
    val smart = remember(tick) { Prefs.modelRouting(c) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OrionUiPalette.panel) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Chat Model", color = OrionUiPalette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Pick a model to chat with", color = OrionUiPalette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(OrionUiPalette.raised)
                    .clickable { c.startActivity(Intent(c, AccountConnectionsActivity::class.java)) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Account connections", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("CHATGPT / GOOGLE", color = OrionUiPalette.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
                    .background(Brush.horizontalGradient(listOf(OrionUiPalette.raised, OrionUiPalette.panel)))
                    .clickable { Prefs.setModelRouting(c, !smart); tick++; onChanged() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(OrionUiPalette.borderStrong.copy(alpha = .35f)), contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontSize = 20.sp) }
                Column(Modifier.weight(1f)) {
                    Text("Smart Mode", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Dynamically picks the best model for speed and quality.", color = Color(0xE6FFFFFF), fontSize = 11.sp)
                }
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (smart) Color.White else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    if (smart) Text("✓", color = OrionUiPalette.background, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Providers.all, key = { it.id }) { p ->
                    val ready = p.noKey || Prefs.hasKey(c, p.id)
                    val selected = p.id == current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(OrionUiPalette.raised)
                            .border(1.dp, if (selected) OrionUiPalette.accent else Color.Transparent, RoundedCornerShape(30.dp))
                            .clickable {
                                if (ready) { Prefs.setProvider(c, false, p.id); tick++; onChanged(); onDismiss() }
                                else if (p.id == "codex" || p.id == "antigravity") {
                                    onDismiss()
                                    c.startActivity(Intent(c, AccountConnectionsActivity::class.java))
                                } else { onDismiss(); onNeedKey() }
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(OrionUiPalette.background), contentAlignment = Alignment.Center) {
                            Text(p.label.take(1).uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(p.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (selected) "IN USE"
                            else if (p.id == "codex" || p.id == "antigravity") "CONNECT"
                            else if (p.noKey) "BUILT-IN"
                            else if (ready) "KEY SET" else "ADD KEY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) OrionUiPalette.accent else if (ready) OrionUiPalette.secondaryText else OrionUiPalette.warning,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(OrionUiPalette.panel)
                                .border(1.dp, if (selected) OrionUiPalette.accent else OrionUiPalette.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
