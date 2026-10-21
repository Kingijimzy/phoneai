package com.phoneai.app

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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

private val ChatInk = Color(0xFF070B0D)
private val ChatPanel = Color(0xFF101719)
private val ChatPanelRaised = Color(0xFF151F21)
private val ChatMint = Color(0xFF72E3CE)
private val ChatCopy = Color(0xFFF0F5F5)
private val ChatMuted = Color(0xFF9BAAAC)
private val ChatHairline = Color(0xFF263638)

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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ChatPanel).border(1.dp, ChatHairline, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowBack, null, tint = ChatCopy, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("ORION", color = ChatCopy, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                Text("PERSONAL INTELLIGENCE", color = ChatMuted, fontSize = 9.sp, letterSpacing = 1.3.sp)
            }
            Row(
                Modifier.clip(CircleShape).background(Color(0xFF12231F)).border(1.dp, Color(0xFF315B51), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (status.contains("listen", true) || status.contains("speak", true)) ChatMint else ChatMuted))
                Spacer(Modifier.width(6.dp))
                Text(status.uppercase().take(14), color = ChatMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(ChatHairline.copy(alpha = 0.55f)))
        Row(
            Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, bottom = 10.dp, top = 10.dp)
                .clip(RoundedCornerShape(16.dp)).background(ChatPanel).border(1.dp, ChatHairline, RoundedCornerShape(16.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModePill("Talk", !commandMode, Modifier.weight(1f)) { onModeChange(false) }
            ModePill("Command", commandMode, Modifier.weight(1f)) { onModeChange(true) }
        }

        if (lines.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(26.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF1C4039), Color(0xFF142122))))
                        .border(1.dp, Color(0xFF37685D), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Waves, null, tint = ChatMint, modifier = Modifier.size(35.dp)) }
                Spacer(Modifier.height(20.dp))
                Text("Let's think it through.", color = ChatCopy, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (commandMode) "Tell Orion what you want done. It will ask before sensitive actions." else "Ask anything. Talk naturally—no automatic web search or phone actions.",
                    color = ChatMuted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(lines, key = { _, item -> item.id }) { _, item ->
                    ChatBubble(item)
                }
            }
        }

        AnimatedVisibility(selectedImage != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp)).background(ChatPanelRaised).border(1.dp, ChatHairline, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedImage?.let { ImageThumbnail(it, Modifier.size(width = 54.dp, height = 54.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("IMAGE ATTACHED", color = ChatMint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Ready for Orion to analyse", color = ChatMuted, fontSize = 11.sp)
                }
                Text("Remove", color = ChatMuted, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onRemoveImage).padding(5.dp))
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(ChatPanel)
                    .border(1.dp, Color(0xFF36524E), RoundedCornerShape(25.dp)).padding(start = 15.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (draft.isEmpty()) Text(if (commandMode) "Tell Orion what to do…" else "Message Orion…", color = ChatMuted, fontSize = 14.sp)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ChatCopy, fontSize = 14.sp, lineHeight = 20.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() || selectedImage != null) {
                                onSend(draft.trim()); draft = ""
                            }
                        }),
                        maxLines = 5
                    )
                }
                ComposerButton(Icons.Rounded.AddPhotoAlternate, "Attach image", onPickImage, secondary = true)
                Spacer(Modifier.width(5.dp))
                ComposerButton(Icons.Rounded.Send, "Send message", {
                    if (draft.isNotBlank() || selectedImage != null) {
                        onSend(draft.trim()); draft = ""
                    }
                }, secondary = false)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, start = 5.dp, end = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sensitive actions require confirmation.", color = ChatMuted, fontSize = 10.sp)
                Text("STOP", color = if (status.contains("think", true) || status.contains("work", true)) Color(0xFFE9B984) else ChatMuted,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onStop).padding(5.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 7.dp, top = 2.dp)
                .clip(RoundedCornerShape(21.dp)).background(ChatPanel).border(1.dp, ChatHairline, RoundedCornerShape(21.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            DockAction("Chat", Icons.Rounded.Send, true, Modifier.weight(1f)) {}
            DockAction("Browser", Icons.Rounded.Public, false, Modifier.weight(1f), onBrowser)
            DockAction("Live", Icons.Rounded.GraphicEq, false, Modifier.weight(1.1f), onVoice)
            DockAction("Tasks", Icons.Rounded.Tune, false, Modifier.weight(1f), onTasks)
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
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF21433B) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) ChatMint else ChatMuted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun ChatBubble(item: OrionChatLine) {
    val mine = item.kind == "me"
    val note = item.kind == "note"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        if (!mine && !note) {
            Text("ORION", color = ChatMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 5.dp, bottom = 3.dp))
        }
        Column(
            Modifier.fillMaxWidth(if (note) 1f else if (mine) .82f else .94f)
                .clip(RoundedCornerShape(if (mine) 20.dp else 16.dp))
                .background(when {
                    note -> Color(0xFF111A1C)
                    mine -> Color(0xFF1C2C2B)
                    else -> ChatPanel
                })
                .border(1.dp, if (mine) Color(0xFF34524C) else ChatHairline, RoundedCornerShape(if (mine) 20.dp else 16.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            if (note) Text("LIVE ACTIVITY", color = ChatMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(item.text, color = if (note) ChatMuted else ChatCopy, fontSize = 14.sp, lineHeight = 21.sp)
            if (item.imageUri != null) {
                Spacer(Modifier.height(9.dp))
                ImageThumbnail(item.imageUri, Modifier.fillMaxWidth().height(190.dp))
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
