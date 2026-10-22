package com.phoneai.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ChatInk = Color(0xFF02080A)
private val ChatPanel = Color(0xFF0A1113)
private val ChatPanelRaised = Color(0xFF10191B)
private val ChatMint = Color(0xFF00FCB6)
private val ChatCopy = Color(0xFFF1F5F4)
private val ChatMuted = Color(0xFFA0AEAD)
private val ChatHairline = Color(0xFF243230)

data class OrionChatLine(
    val id: Long,
    val kind: String,
    val text: String,
    val imageUri: Uri? = null
)

@Composable
fun OrionComposeChatScreen(
    lines: List<OrionChatLine>,
    status: String,
    commandMode: Boolean,
    selectedImage: Uri?,
    onBack: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onVoice: () -> Unit,
    onBrowser: () -> Unit,
    onTasks: () -> Unit,
    onStop: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(ChatInk).imePadding()) {
        // Editorial header: quiet branding, no oversized pill around every element.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 18.dp, top = 14.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF0D1416))
                    .border(1.dp, ChatHairline, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowBack, null, tint = ChatCopy, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Orion", color = ChatCopy, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (status.contains("listen", true) || status.contains("speak", true)) ChatMint else Color(0xFF66817B)))
                    Text(status.replaceFirstChar { it.uppercase() }.take(24), color = ChatMuted, fontSize = 11.sp)
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF0D1517))
                    .border(1.dp, ChatHairline, RoundedCornerShape(9.dp)).clickable { onModeChange(!commandMode) }
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (commandMode) Icons.Rounded.Tune else Icons.Rounded.Waves, null,
                        tint = ChatMint, modifier = Modifier.size(15.dp))
                    Text(if (commandMode) "Command" else "Talk", color = ChatCopy, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ChatHairline.copy(alpha = .55f)))

        if (lines.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 25.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF254A43), Color(0xFF111B1D))))
                        .border(1.dp, Color(0xFF355A52), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.height(27.dp))
                Text(if (commandMode) "What should I take care of?" else "What’s on your mind?", color = ChatCopy,
                    fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
                Spacer(Modifier.height(11.dp))
                Text(
                    if (commandMode) "Describe the task. Orion will explain what it plans to do and ask before sensitive actions."
                    else "Talk naturally. Orion answers from the conversation first — it won’t search the web or control your phone unless you ask.",
                    color = ChatMuted, fontSize = 13.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(27.dp))
                Text("SUGGESTED PROMPTS", color = Color(0xFF758A88), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
                Spacer(Modifier.height(10.dp))
                listOf("Help me think through an idea", "Explain something simply", "Help me plan my next step").forEachIndexed { index, prompt ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSend(prompt) }.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(prompt, color = ChatCopy.copy(alpha = .88f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF718783), modifier = Modifier.size(16.dp))
                    }
                    if (index < 2) Box(Modifier.fillMaxWidth().height(1.dp).background(ChatHairline.copy(alpha = .6f)))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 20.dp)
            ) {
                itemsIndexed(lines, key = { _, item -> item.id }) { _, item -> ChatBubble(item) }
            }
        }

        AnimatedVisibility(selectedImage != null, enter = fadeIn(animationSpec = tween(180))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp)).background(ChatPanelRaised)
                    .border(1.dp, ChatHairline, RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedImage?.let { ImageThumbnail(it, Modifier.size(width = 48.dp, height = 48.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Image attached", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Ready to analyse", color = ChatMuted, fontSize = 11.sp)
                }
                Text("Remove", color = ChatMint, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onRemoveImage).padding(7.dp))
            }
        }

        // Composer is the primary surface; actions remain reachable without a second oversized dock.
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF131D1F), Color(0xFF0B1214))))
                    .border(1.dp, Color(0xFF2A3B39), RoundedCornerShape(17.dp))
                    .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    if (draft.isEmpty()) Text(if (commandMode) "Describe the task…" else "Message Orion…", color = ChatMuted, fontSize = 14.sp)
                    BasicTextField(
                        value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ChatCopy, fontSize = 14.sp, lineHeight = 21.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() || selectedImage != null) { onSend(draft.trim()); draft = "" }
                        }), maxLines = 5
                    )
                }
                ComposerButton(Icons.Rounded.AddPhotoAlternate, "Attach image", onPickImage, secondary = true)
                Spacer(Modifier.width(5.dp))
                ComposerButton(Icons.Rounded.Send, "Send message", {
                    if (draft.isNotBlank() || selectedImage != null) { onSend(draft.trim()); draft = "" }
                }, secondary = false)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, start = 5.dp, end = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live voice", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onVoice))
                    Text("Browser", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onBrowser))
                    Text("Tasks", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onTasks))
                }
                Text("STOP", color = if (status.contains("think", true) || status.contains("work", true)) Color(0xFFE9B984) else Color(0xFF647775),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onStop).padding(4.dp))
            }
        }
    }
}


@Composable
private fun ImageThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = when {
                            bounds.outWidth > 2400 || bounds.outHeight > 2400 -> 4
                            bounds.outWidth > 1200 || bounds.outHeight > 1200 -> 2
                            else -> 1
                        }
                    }
                    BitmapFactory.decodeStream(input, null, options)
                }
            } catch (_: Exception) { null }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(ChatPanelRaised)
            .border(1.dp, ChatHairline, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Selected image preview",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Image preview unavailable",
                tint = ChatMuted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pillFill by animateColorAsState(
        targetValue = if (selected) Color(0xFF21433B) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "mode-pill-fill"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) ChatMint else ChatMuted,
        animationSpec = tween(durationMillis = 180),
        label = "mode-pill-label"
    )
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(pillFill)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = labelColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun ChatBubble(item: OrionChatLine) {
    val mine = item.kind == "me"
    val note = item.kind == "note"
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(220)) +
            slideInVertically(animationSpec = tween(220)) { it / 16 }
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            if (!mine && !note) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 7.dp)
                ) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF28564B), Color(0xFF172322))))
                            .border(1.dp, Color(0xFF355A52), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(13.dp)) }
                    Text("Orion", color = ChatCopy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("AI assistant", color = Color(0xFF71817F), fontSize = 10.sp)
                }
            }
            Column(
                Modifier.fillMaxWidth(if (note) 1f else if (mine) .86f else .98f)
                    .then(
                        if (mine || note) Modifier.clip(RoundedCornerShape(17.dp))
                            .background(if (note) Color(0xFF11191B) else Color(0xFF1A2928))
                            .border(1.dp, if (mine) Color(0xFF314A45) else ChatHairline, RoundedCornerShape(17.dp))
                        else Modifier
                    )
                    .padding(horizontal = if (mine || note) 14.dp else 0.dp, vertical = if (mine || note) 11.dp else 0.dp)
            ) {
                if (note) {
                    Text("ACTIVITY", color = ChatMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Text(item.text, color = if (note) ChatMuted else ChatCopy, fontSize = 14.sp, lineHeight = 22.sp)
                if (item.imageUri != null) {
                    Spacer(Modifier.height(9.dp))
                    ImageThumbnail(item.imageUri, Modifier.fillMaxWidth().height(190.dp))
                }
            }
        }
    }
}

@Composable
private fun ComposerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, secondary: Boolean) {
    Box(
        Modifier.size(43.dp).clip(CircleShape)
            .background(if (secondary) Color(0xFF1A2B2B) else ChatMint)
            .border(1.dp, if (secondary) ChatHairline else Color(0xFFB6F9E9), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = if (secondary) ChatMint else ChatInk, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun DockAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0xFF1B3430) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, null, tint = if (selected) ChatMint else ChatMuted, modifier = Modifier.size(19.dp))
        Text(title, color = if (selected) ChatMint else ChatMuted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}
