package com.phoneai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Empty-chat home: the glass orb, a greeting, a big question and tappable suggestion chips over a night-to-indigo wash. */
@Composable
internal fun OrionHome(commandMode: Boolean, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val name = Prefs.userName(LocalContext.current).substringBefore(' ').ifBlank { "there" }
    val chips = if (commandMode) listOf("Check my battery and storage", "Summarise my notifications", "Build me a small tool", "What can you do?")
    else listOf("Tell me a fun fact", "Help me think something through", "Explain it simply", "Plan my next step")
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF050716), Color(0xFF16206B), Color(0xFF2E5FC0))))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            OrionOrb(Modifier.size(250.dp))
            Spacer(Modifier.height(6.dp))
            Text("Hello $name", color = Color(0xFF9AA0AD), fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (commandMode) "What should I\ndo for you?" else "How can I help\nyou today?", color = Color.White,
                fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chips.forEach { c ->
                    Text(c, color = Color(0xFFD8DCFF), fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x334F5BFF))
                            .border(1.dp, Color(0x445C6BFF), RoundedCornerShape(22.dp))
                            .clickable { onSend(c) }.padding(horizontal = 16.dp, vertical = 11.dp))
                }
            }
        }
    }
}
