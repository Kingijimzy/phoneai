package com.phoneai.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Orion's code space: tabbed editor with syntax colouring, a dual-pane file manager (this phone | the PRoot workspace)
 * and the Linux terminal as a bottom sheet, in a dark Material 3 theme. Everything else the old Studio had
 * (git, build, runtime, packages, extensions) stays one tap away under Tools.
 */
class CodeSpaceActivity : ComponentActivity() {
    private class OpenFile(val file: File, val rel: String, text: String) {
        var text by mutableStateOf(text)
        var saved by mutableStateOf(text)
        val dirty get() = text != saved
    }

    private val tabs = mutableStateListOf<OpenFile>()
    private var active by mutableStateOf(0)
    private val terminalLog = mutableStateOf("")

    private fun workspaceRoot(): File = OrionProjectContext.root(this)
    private fun phoneRoot(): File = (getExternalFilesDir(null) ?: filesDir).also { it.mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("file")?.let { openRelative(it) }
        intent.getStringExtra("snippet")?.let { code ->
            val ext = intent.getStringExtra("lang")?.take(8)?.filter { it.isLetterOrDigit() }?.ifBlank { null } ?: "txt"
            tabs.add(OpenFile(File(workspaceRoot(), "snippet.$ext"), "snippet.$ext", code)); active = tabs.lastIndex
        }
        setContent { CodeSpaceTheme { CodeSpaceScreen() } }
    }

    private fun openRelative(rel: String) {
        val f = CodeSpaceModel.resolve(workspaceRoot(), rel) ?: return
        openFile(f)
    }

    private fun openFile(f: File) {
        val root = workspaceRoot().canonicalFile
        val rel = runCatching { f.canonicalFile.relativeTo(root).path }.getOrDefault(f.name)
        val existing = tabs.indexOfFirst { it.file.canonicalPath == f.canonicalPath }
        if (existing >= 0) { active = existing; return }
        if (!f.isFile || f.length() > CodeSpaceModel.MAX_EDIT_BYTES) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        tabs.add(OpenFile(f, rel, text)); active = tabs.lastIndex
    }

    private fun save(t: OpenFile): Boolean = runCatching { t.file.parentFile?.mkdirs(); t.file.writeText(t.text); t.saved = t.text; true }.getOrDefault(false)

    private fun runInTerminal(command: String) {
        terminalLog.value = (terminalLog.value + "\n$ $command\n").takeLast(24_000)
        Thread({
            val result = runCatching { ShellRunner.runStreaming(this, command, OrionUbuntu.where(this)) { chunk -> terminalLog.value = (terminalLog.value + chunk).takeLast(24_000) } }
                .getOrElse { "error: ${it.message}" }
            if (result.startsWith("error:")) terminalLog.value = (terminalLog.value + "\n" + result + "\n").takeLast(24_000)
        }, "code-space-shell").start()
    }

    @Composable
    private fun CodeSpaceTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = OrionUiPalette.accent, onPrimary = Color(0xFF101010),
                background = OrionUiPalette.background, onBackground = OrionUiPalette.text,
                surface = OrionUiPalette.panel, onSurface = OrionUiPalette.text,
                surfaceVariant = OrionUiPalette.raised, onSurfaceVariant = OrionUiPalette.secondaryText,
                outline = OrionUiPalette.borderStrong, error = OrionUiPalette.danger
            ),
            content = content
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CodeSpaceScreen() {
        var showFiles by remember { mutableStateOf(false) }
        var showTerminal by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        val current = tabs.getOrNull(active)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Code Space", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    IconButton(onClick = { showFiles = true }) { Icon(Icons.Rounded.FolderOpen, "Files", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { current?.let { t -> if (save(t)) notice = "Saved ${t.rel}" else notice = "Could not save ${t.rel}" } }, enabled = current != null) { Icon(Icons.Rounded.Save, "Save", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = {
                        current?.let { t ->
                            save(t)
                            val cmd = CodeSpaceModel.runCommandFor(t.rel)
                            if (cmd == null) notice = "No runner for ${t.rel}; use the terminal." else { showTerminal = true; runInTerminal(cmd) }
                        }
                    }, enabled = current != null) { Icon(Icons.Rounded.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { showTerminal = true }) { Icon(Icons.Rounded.Terminal, "Terminal", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { startActivity(Intent(this@CodeSpaceActivity, StudioActivity::class.java)) }) { Icon(Icons.Rounded.Tune, "Tools", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // tabs
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
                    tabs.forEachIndexed { i, t ->
                        val selected = i == active
                        Row(
                            Modifier.clickable { active = i }.background(if (selected) MaterialTheme.colorScheme.background else Color.Transparent)
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((if (t.dirty) "● " else "") + t.file.name, color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                            IconButton(onClick = { tabs.removeAt(i); if (active >= tabs.size) active = (tabs.size - 1).coerceAtLeast(0) }, modifier = Modifier.width(30.dp).height(30.dp)) {
                                Icon(Icons.Rounded.Close, "Close tab", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp).height(14.dp))
                            }
                        }
                    }
                }
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
                // editor
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (current == null) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No file open", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Open the file manager, or ask Orion to build something and it will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            TextButton(onClick = { showFiles = true }) { Text("Open files") }
                        }
                    } else CodeEditor(current)
                }
            }
        }
        if (showFiles) FileManagerSheet(onDismiss = { showFiles = false }, onOpen = { openFile(it); showFiles = false })
        if (showTerminal) TerminalSheet(onDismiss = { showTerminal = false })
    }

    @Composable
    private fun CodeEditor(t: OpenFile) {
        val lang = remember(t.file.name) { OrionStudioEditor.langOf(t.file.name) }
        val transform = remember(lang) { VisualTransformation { TransformedText(CodeSpaceModel.annotate(lang, it.text), OffsetMapping.Identity) } }
        val style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
        val lines = t.text.count { it == '\n' } + 1
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
                Text((1..lines.coerceAtMost(5000)).joinToString("\n"), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp))
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).fillMaxHeight().padding(10.dp)) {
                BasicTextField(
                    value = t.text, onValueChange = { t.text = it }, textStyle = style, softWrap = false,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), visualTransformation = transform,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FileManagerSheet(onDismiss: () -> Unit, onOpen: (File) -> Unit) {
        val ctx = LocalContext.current
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val phone = phoneRoot(); val work = workspaceRoot().also { it.mkdirs() }
        var left by remember { mutableStateOf(phone) }
        var right by remember { mutableStateOf(work) }
        var leftSel by remember { mutableStateOf<File?>(null) }
        var rightSel by remember { mutableStateOf<File?>(null) }
        var refresh by remember { mutableStateOf(0) }
        var message by remember { mutableStateOf("") }
        var asking by remember { mutableStateOf<String?>(null) } // "file" | "folder"
        var confirmDelete by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val focusRight = rightSel != null || leftSel == null
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().height(520.dp).padding(horizontal = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Files", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { asking = "file"; name = "" }) { Icon(Icons.Rounded.NoteAdd, "New file", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { asking = "folder"; name = "" }) { Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { confirmDelete = true }, enabled = (if (focusRight) rightSel else leftSel) != null) { Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilePane("This phone", left, phone, leftSel, refresh, Modifier.weight(1f), onDir = { left = it; leftSel = null }, onSelect = { leftSel = it; rightSel = null })
                    FilePane("Workspace (Linux)", right, work, rightSel, refresh, Modifier.weight(1f), onDir = { right = it; rightSel = null }, onSelect = { f ->
                        if (f.isFile && rightSel == f) onOpen(f) else { rightSel = f; leftSel = null }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { leftSel?.let { message = CodeSpaceModel.copyInto(it, right); refresh++ } }, enabled = leftSel != null) { Text("Copy to workspace →") }
                    TextButton(onClick = { rightSel?.let { message = CodeSpaceModel.copyInto(it, left); refresh++ } }, enabled = rightSel != null) { Text("← Copy to phone") }
                    TextButton(onClick = { rightSel?.takeIf { it.isFile }?.let(onOpen) }, enabled = rightSel?.isFile == true) { Text("Open") }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        asking?.let { kind ->
            val target = if (focusRight) right else left
            AlertDialog(
                onDismissRequest = { asking = null }, containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(if (kind == "file") "New file" else "New folder") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        if (CodeSpaceModel.validNewName(name)) {
                            val f = File(target, CodeSpaceModel.uniqueName(target, name.trim()))
                            runCatching { if (kind == "file") f.createNewFile() else f.mkdirs() }
                            message = "Created ${f.name}"; refresh++
                        } else message = "Use a simple name without slashes."
                        asking = null
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } }
            )
        }
        if (confirmDelete) {
            val victim = if (focusRight) rightSel else leftSel
            AlertDialog(
                onDismissRequest = { confirmDelete = false }, containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Delete ${victim?.name.orEmpty()}?") },
                text = { Text("This removes it from the device. It cannot be undone.") },
                confirmButton = { TextButton(onClick = {
                    victim?.let { message = if (it.deleteRecursively()) "Deleted ${it.name}" else "Could not delete ${it.name}" }
                    leftSel = null; rightSel = null; confirmDelete = false; refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    private fun FilePane(title: String, dir: File, root: File, selected: File?, refresh: Int, modifier: Modifier, onDir: (File) -> Unit, onSelect: (File) -> Unit) {
        val entries = remember(dir, refresh) { CodeSpaceModel.list(dir) }
        val atRoot = dir.canonicalPath == root.canonicalPath
        Column(modifier.fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (atRoot) "/" else "/" + dir.relativeTo(root).path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!atRoot) IconButton(onClick = { dir.parentFile?.let(onDir) }, modifier = Modifier.width(30.dp).height(30.dp)) { Icon(Icons.Rounded.ArrowUpward, "Up", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (entries.isEmpty()) Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.name }) { e ->
                    val f = File(dir, e.name)
                    val sel = selected?.canonicalPath == f.canonicalPath
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { if (e.isDir) onDir(f) else onSelect(f) }.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(if (e.isDir) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp).height(18.dp))
                        Text(e.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (e.isDir) Box(Modifier.clickable { onSelect(f) }.padding(4.dp)) { Text("select", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TerminalSheet(onDismiss: () -> Unit) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        var input by remember { mutableStateOf("") }
        val scroll = rememberScrollState()
        androidx.compose.runtime.LaunchedEffect(terminalLog.value.length) { scroll.animateScrollTo(scroll.maxValue) }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF0B0B0C)) {
            Column(Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 12.dp)) {
                Text("Terminal · " + (if (OrionUbuntu.isInstalled(this@CodeSpaceActivity)) "Ubuntu" else "Linux") + " · /workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).verticalScroll(scroll)) {
                    Text(terminalLog.value.ifBlank { "Type a command below." }, color = Color(0xFFD7D7D7), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text("$", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
                    BasicTextField(
                        value = input, onValueChange = { input = it }, singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = TextStyle(color = Color(0xFFEDEDED), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { val c = input.trim(); if (c.isNotEmpty()) { runInTerminal(c); input = "" } }) { Text("Run") }
                }
            }
        }
    }
}
