package com.phoneai.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Orion's code space, built to feel like a desktop IDE on a phone: a dual-pane file manager (this phone | the Linux
 * workspace) with breadcrumbs, typed file icons, sizes and dates and folder sync; a full-screen tabbed editor with a line
 * gutter and syntax colour that slides up when a file opens; the Linux terminal as a bottom sheet; and a plugin manager.
 */
class CodeSpaceActivity : ComponentActivity() {
    private class OpenFile(val file: File, val rel: String, text: String) {
        var value by mutableStateOf(TextFieldValue(text))
        var saved by mutableStateOf(text)
        val dirty get() = value.text != saved
    }

    private val tabs = mutableStateListOf<OpenFile>()
    private var active by mutableStateOf(0)
    private var editorOpen by mutableStateOf(false)
    private val terminalLog = mutableStateOf("")

    private fun workspaceRoot(): File = ShellRunner.workspace(this).also { it.mkdirs() }
    private fun projectRoot(): File = OrionProjectContext.root(this).also { it.mkdirs() }
    private fun appFilesRoot(): File = (getExternalFilesDir(null) ?: filesDir).also { it.mkdirs() }
    private fun storageRoot(): File = Environment.getExternalStorageDirectory()
    private fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("file")?.let { openRelative(it) }
        intent.getStringExtra("snippet")?.let { code ->
            val ext = intent.getStringExtra("lang")?.take(8)?.filter { it.isLetterOrDigit() }?.ifBlank { null } ?: "txt"
            tabs.add(OpenFile(File(projectRoot(), "snippet.$ext"), "snippet.$ext", code)); active = tabs.lastIndex; editorOpen = true
        }
        setContent { CodeSpaceTheme { CodeSpaceScreen() } }
    }

    private fun openRelative(rel: String) {
        val f = CodeSpaceModel.resolve(projectRoot(), rel) ?: return
        openFile(f)
    }

    private fun openFile(f: File) {
        val existing = tabs.indexOfFirst { it.file.canonicalPath == f.canonicalPath }
        if (existing >= 0) { active = existing; editorOpen = true; return }
        if (!f.isFile || f.length() > CodeSpaceModel.MAX_EDIT_BYTES) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        val rel = runCatching { f.canonicalFile.relativeTo(workspaceRoot().canonicalFile).path }.getOrNull()?.takeIf { !it.startsWith("..") } ?: f.name
        tabs.add(OpenFile(f, rel, text)); active = tabs.lastIndex; editorOpen = true
    }

    private fun save(t: OpenFile): Boolean = runCatching { t.file.parentFile?.mkdirs(); t.file.writeText(t.value.text); t.saved = t.value.text; true }.getOrDefault(false)

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

    /** A desktop-style toolbar button: a solid 42dp tile with a 22dp glyph, so it reads at a glance. */
    @Composable
    private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, filled: Boolean = false) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                .background(if (filled) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, if (filled) tint.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp)) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CodeSpaceScreen() {
        var showTerminal by remember { mutableStateOf(intent.getStringExtra("page") == "terminal") }
        var showPlugins by remember { mutableStateOf(intent.getStringExtra("page") == "extensions") }
        BackHandler(enabled = editorOpen) { editorOpen = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                HomeScreen(onTerminal = { showTerminal = true }, onPlugins = { showPlugins = true })
                androidx.compose.animation.AnimatedVisibility(
                    visible = editorOpen && tabs.isNotEmpty(),
                    enter = androidx.compose.animation.slideInVertically { it },
                    exit = androidx.compose.animation.slideOutVertically { it }
                ) { EditorScreen(onTerminal = { showTerminal = true }) }
            }
        }
        if (showTerminal) TerminalSheet(onDismiss = { showTerminal = false })
        if (showPlugins) PluginSheet(onDismiss = { showPlugins = false })
    }

    // ---------------------------------------------------------------- home: dual-pane file manager

    @Composable
    private fun HomeScreen(onTerminal: () -> Unit, onPlugins: () -> Unit) {
        val ctx = LocalContext.current
        val work = remember { workspaceRoot() }
        var leftKey by remember { mutableStateOf(if (allFilesAccess()) "storage" else "app") }
        var rightKey by remember { mutableStateOf("workspace") }
        fun leftRoot() = if (leftKey == "storage") storageRoot() else appFilesRoot()
        fun rightRoot() = if (rightKey == "project") projectRoot() else work
        var left by remember { mutableStateOf(leftRoot()) }
        var right by remember { mutableStateOf(rightRoot()) }
        var leftSel by remember { mutableStateOf<File?>(null) }
        var rightSel by remember { mutableStateOf<File?>(null) }
        var refresh by remember { mutableStateOf(0) }
        var message by remember { mutableStateOf("") }
        var asking by remember { mutableStateOf<String?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val focusRight = rightSel != null || leftSel == null
        val synced = remember(left, right, refresh) { OrionFolderSync.isSynced(ctx, left, right) }
        // Folders that are synced keep both sides level in the background; also refresh the listing now and then.
        LaunchedEffect(refresh) { while (true) { kotlinx.coroutines.delay(20_000); if (OrionFolderSync.pairs(ctx).isNotEmpty()) { Thread { OrionFolderSync.syncAll(ctx) }.start(); refresh++ } } }

        fun pickLeft(key: String) {
            if (key == "storage" && !allFilesAccess()) {
                message = "Allow All files access for Orion, then come back and tap Storage again."
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                return
            }
            leftKey = key; left = leftRoot(); leftSel = null
        }
        fun pickRight(key: String) { rightKey = key; right = rightRoot(); rightSel = null }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Code Space", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(if (synced) "Sync on · this folder pair stays level" else "Phone | Linux workspace", color = if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(if (synced) Icons.Rounded.Sync else Icons.Rounded.SyncDisabled, "Sync this folder pair", onClick = {
                    if (left.canonicalPath == storageRoot().canonicalPath || right.canonicalPath == work.canonicalPath) message = "Open a folder inside storage and inside the workspace first, then sync that pair."
                    else Thread { val m = OrionFolderSync.toggle(ctx, left, right); runOnUiThread { message = m; refresh++ } }.start()
                }, tint = if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, filled = synced)
                ToolButton(Icons.Rounded.NoteAdd, "New file", { asking = "file"; name = "" })
                ToolButton(Icons.Rounded.CreateNewFolder, "New folder", { asking = "folder"; name = "" })
                ToolButton(Icons.Rounded.Extension, "Plugins", onPlugins)
                ToolButton(Icons.Rounded.Terminal, "Terminal", onTerminal)
                ToolButton(Icons.Rounded.Tune, "Studio tools", { startActivity(Intent(this@CodeSpaceActivity, StudioActivity::class.java)) })
            }
            if (tabs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { editorOpen = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  Editor · ${tabs.size} open" + (tabs.getOrNull(active)?.let { " · " + it.file.name } ?: ""), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("OPEN", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilePane(
                    "This phone", listOf("app" to "App", "storage" to "Storage"), leftKey, ::pickLeft, left, if (leftKey == "storage") storageRoot() else appFilesRoot(),
                    if (leftKey == "storage") "Storage" else "App files", leftSel, refresh, Modifier.weight(1f),
                    onDir = { left = it; leftSel = null }, onSelect = { leftSel = it; rightSel = null }
                )
                FilePane(
                    "Linux workspace", listOf("workspace" to "Workspace", "project" to "Project"), rightKey, ::pickRight, right, if (rightKey == "project") projectRoot() else work,
                    if (rightKey == "project") "Project" else "Workspace", rightSel, refresh, Modifier.weight(1f),
                    onDir = { right = it; rightSel = null }, onSelect = { f -> if (f.isFile && rightSel == f) openFile(f) else { rightSel = f; leftSel = null } }
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leftSel?.let { message = CodeSpaceModel.copyInto(it, right); refresh++ } }, enabled = leftSel != null) { Text("To workspace →", fontSize = 12.sp) }
                TextButton(onClick = { rightSel?.let { message = CodeSpaceModel.copyInto(it, left); refresh++ } }, enabled = rightSel != null) { Text("← To phone", fontSize = 12.sp) }
                TextButton(onClick = { (if (focusRight) rightSel else leftSel)?.takeIf { it.isFile }?.let { openFile(it) } }, enabled = (if (focusRight) rightSel else leftSel)?.isFile == true) { Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                ToolButton(Icons.Rounded.Delete, "Delete", { confirmDelete = true }, enabled = (if (focusRight) rightSel else leftSel) != null, tint = MaterialTheme.colorScheme.error)
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        asking?.let { kind ->
            val target = if (focusRight) right else left
            AlertDialog(
                onDismissRequest = { asking = null }, containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(if (kind == "file") "New file in ${target.name.ifBlank { "/" }}" else "New folder in ${target.name.ifBlank { "/" }}") },
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

    private fun iconFor(kind: CodeSpaceModel.Kind): ImageVector = when (kind) {
        CodeSpaceModel.Kind.FOLDER -> Icons.Rounded.Folder
        CodeSpaceModel.Kind.PYTHON, CodeSpaceModel.Kind.KOTLIN, CodeSpaceModel.Kind.SCRIPT -> Icons.Rounded.Code
        CodeSpaceModel.Kind.JSON -> Icons.Rounded.DataObject
        CodeSpaceModel.Kind.WEB -> Icons.Rounded.Language
        CodeSpaceModel.Kind.IMAGE -> Icons.Rounded.Image
        CodeSpaceModel.Kind.ARCHIVE -> Icons.Rounded.FolderZip
        CodeSpaceModel.Kind.CONFIG -> Icons.Rounded.Settings
        else -> Icons.Rounded.Description
    }

    @Composable
    private fun FilePane(
        title: String, roots: List<kotlin.Pair<String, String>>, rootKey: String, onRoot: (String) -> Unit,
        dir: File, root: File, rootLabel: String, selected: File?, refresh: Int, modifier: Modifier,
        onDir: (File) -> Unit, onSelect: (File) -> Unit
    ) {
        val entries = remember(dir, refresh) { CodeSpaceModel.list(dir) }
        val crumbs = remember(dir, root) { CodeSpaceModel.breadcrumbs(root, dir, rootLabel) }
        val atRoot = crumbs.size <= 1
        val shape = RoundedCornerShape(14.dp)
        Column(modifier.fillMaxHeight().clip(shape).background(MaterialTheme.colorScheme.background).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                roots.forEach { (key, label) ->
                    val on = key == rootKey
                    Text(
                        label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onRoot(key) }.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!atRoot) Icon(Icons.Rounded.ArrowUpward, "Up one folder", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { dir.parentFile?.let(onDir) }.padding(5.dp))
            }
            // Breadcrumb: Workspace › projects › phoneai, every segment tappable.
            val crumbScroll = rememberScrollState()
            LaunchedEffect(crumbs.size) { crumbScroll.scrollTo(crumbScroll.maxValue) }
            Row(Modifier.fillMaxWidth().horizontalScroll(crumbScroll).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                crumbs.forEachIndexed { i, (label, f) ->
                    if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(
                        label, fontSize = 11.sp, maxLines = 1,
                        color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (i == crumbs.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { onDir(f) }.padding(vertical = 4.dp)
                    )
                }
            }
            Text(title.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            if (entries.isEmpty()) Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.name }) { e ->
                    val f = File(dir, e.name)
                    val sel = selected?.canonicalPath == f.canonicalPath
                    val kind = CodeSpaceModel.kindOf(e.name, e.isDir)
                    val tint = Color(kind.color)
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { if (e.isDir) onDir(f) else onSelect(f) }.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(iconFor(kind), kind.label, tint = tint, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (e.isDir) "Folder" else CodeSpaceModel.formatSize(e.size)) + CodeSpaceModel.formatDate(e.modified).let { if (it.isEmpty()) "" else " · $it" },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (e.isDir) Text(if (sel) "selected" else "select", color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(f) }.padding(horizontal = 6.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- editor: full screen, slides up

    @Composable
    private fun EditorScreen(onTerminal: () -> Unit) {
        var notice by remember { mutableStateOf("") }
        var fontSize by remember { mutableStateOf(13) }
        val current = tabs.getOrNull(active)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(Icons.Rounded.ArrowBackIosNew, "Back to files", { editorOpen = false })
                    Column(Modifier.weight(1f)) {
                        Text(current?.file?.name.orEmpty(), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current?.rel.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ToolButton(Icons.Rounded.Save, "Save", { current?.let { t -> notice = if (save(t)) "Saved ${t.rel}" else "Could not save ${t.rel}" } }, enabled = current != null)
                    ToolButton(Icons.Rounded.PlayArrow, "Run", {
                        current?.let { t ->
                            save(t)
                            val cmd = CodeSpaceModel.runCommandFor(t.rel)
                            if (cmd == null) notice = "No runner for ${t.rel}; use the terminal." else { onTerminal(); runInTerminal("cd /workspace && $cmd") }
                        }
                    }, enabled = current != null, tint = MaterialTheme.colorScheme.primary, filled = true)
                    ToolButton(Icons.Rounded.Terminal, "Terminal", onTerminal)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
                    tabs.forEachIndexed { i, t ->
                        val selected = i == active
                        Row(
                            Modifier.clickable { active = i }.background(if (selected) MaterialTheme.colorScheme.background else Color.Transparent)
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text((if (t.dirty) "● " else "") + t.file.name, color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                            Icon(Icons.Rounded.Close, "Close tab", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { tabs.removeAt(i); if (active >= tabs.size) active = (tabs.size - 1).coerceAtLeast(0); if (tabs.isEmpty()) editorOpen = false }.padding(8.dp))
                        }
                    }
                }
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) { current?.let { CodeEditor(it, fontSize) } }
                current?.let { t ->
                    val text = t.value.text
                    val cursor = t.value.selection.start.coerceIn(0, text.length)
                    val line = text.take(cursor).count { it == '\n' } + 1
                    val col = cursor - (text.take(cursor).lastIndexOf('\n') + 1) + 1
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ln $line, Col $col · ${text.count { it == '\n' } + 1} lines · ${OrionStudioEditor.langOf(t.file.name)} · UTF-8", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("A−", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { fontSize = (fontSize - 1).coerceAtLeast(9) }.padding(horizontal = 12.dp, vertical = 4.dp))
                        Text("A+", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { fontSize = (fontSize + 1).coerceAtMost(24) }.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun CodeEditor(t: OpenFile, fontSizeSp: Int) {
        val lang = remember(t.file.name) { OrionStudioEditor.langOf(t.file.name) }
        val transform = remember(lang) { VisualTransformation { TransformedText(CodeSpaceModel.annotate(lang, it.text), OffsetMapping.Identity) } }
        val lineHeight = (fontSizeSp * 1.5f).sp
        val style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, lineHeight = lineHeight)
        val lineCount = t.value.text.count { it == '\n' } + 1
        val gutter = (1..lineCount).joinToString("\n")
        val digits = lineCount.toString().length.coerceAtLeast(2)
        val gutterWidth: Dp = (digits * fontSizeSp * 0.62f + 16f).dp
        // Lines stay on one row each (the field scrolls sideways), so every gutter number lines up with its line.
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                gutter, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.End,
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier.width(gutterWidth).background(MaterialTheme.colorScheme.surface).padding(top = 12.dp, bottom = 12.dp, end = 8.dp)
            )
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = t.value, onValueChange = { t.value = it }, textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), visualTransformation = transform,
                    modifier = Modifier.defaultMinSize(minWidth = 360.dp, minHeight = 400.dp).padding(start = 10.dp, top = 12.dp, end = 40.dp, bottom = 12.dp)
                )
            }
        }
    }

    // ---------------------------------------------------------------- sheets

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PluginSheet(onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var tick by remember { mutableStateOf(0) }
        var note by remember { mutableStateOf("") }
        val installed = remember(tick) { OrionPlugins.installed(ctx) }
        val have = installed.map { it.first.id }.toSet()
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Plugins", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Each plugin is a manifest of tools Orion can call. It lists the permissions it wants; nothing runs until they are granted.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (installed.isEmpty()) Text("Nothing installed yet. Pick one below.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                installed.forEach { (p, on) ->
                    val granted = OrionPlugins.isGranted(ctx, p.id)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.id + "  " + p.version, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(p.description.ifBlank { "No description" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Switch(checked = on, onCheckedChange = { note = OrionPlugins.setEnabled(ctx, p.id, it); tick++ })
                        }
                        Text("Tools: " + p.tools.joinToString { it.name }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Text("Permissions: " + p.permissions.joinToString().ifBlank { "none" } + if (granted) " · granted" else " · NOT granted", color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!granted) TextButton(onClick = { note = OrionPlugins.setGranted(ctx, p.id, true); tick++ }) { Text("Grant") }
                            else if (p.permissions.isNotEmpty()) TextButton(onClick = { note = OrionPlugins.setGranted(ctx, p.id, false); tick++ }) { Text("Revoke") }
                            TextButton(onClick = { Thread { val m = OrionPlugins.remove(ctx, p.id); runOnUiThread { note = m; tick++ } }.start() }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Text("CATALOG", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                OrionPlugins.catalog.forEach { (id, manifest) ->
                    val p = OrionPlugins.parse(manifest).first ?: return@forEach
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(id, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(p.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text(p.permissions.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                        if (id in have) Text("Installed", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        else TextButton(onClick = { Thread { val m = OrionPlugins.install(ctx, manifest, trusted = true); runOnUiThread { note = m.take(160); tick++ } }.start() }) { Text("Install") }
                    }
                }
                if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TerminalSheet(onDismiss: () -> Unit) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        var input by remember { mutableStateOf("") }
        val scroll = rememberScrollState()
        LaunchedEffect(terminalLog.value.length) { scroll.animateScrollTo(scroll.maxValue) }
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
