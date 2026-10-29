package com.phoneai.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Real self-extension workflow: save draft -> execute sandbox tests -> explicit approval -> promote. */
class SelfExtensionLabActivity : ComponentActivity() {
    private val ink = Color(0xFF080808)
    private val panel = Color(0xFF171717)
    private val line = Color(0xFF343434)
    private val white = Color(0xFFF4F5F6)
    private val muted = Color(0xFF9A9DA1)
    private val blue = Color(0xFF7EA9D6)
    private val store by lazy { SelfExtendingExtensionStore(applicationContext) }
    private val sandbox by lazy {
        RestrictedPythonSandbox { source, inputJson ->
            OrionPythonRuntime.runExtension(applicationContext, source, inputJson)
        }
    }
    private val reports = mutableStateMapOf<String, ExtensionTestReport>()
    private val extensionsState = mutableStateOf<List<GeneratedExtension>>(emptyList())
    private val statusState = mutableStateOf("Loading extension registry…")
    private val idState = mutableStateOf("")
    private val nameState = mutableStateOf("")
    private val descriptionState = mutableStateOf("")
    private val sourceState = mutableStateOf("RESULT = INPUT[\"value\"] * 2")
    private val testsState = mutableStateOf("[{\"input\": {\"value\": 3}, \"expect\": 6}]")
    private val runInputState = mutableStateOf("{\"value\": 5}")
    private val runOutputState = mutableStateOf("")
    private val confirmEnableState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 8, 8)
        window.navigationBarColor = android.graphics.Color.rgb(8, 8, 8)
        refresh()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = blue, onPrimary = ink, background = ink, surface = panel,
                onSurface = white, secondary = blue
            )) {
                Surface(Modifier.fillMaxSize(), color = ink) {
                    SelfExtensionLabScreen(
                        status = statusState.value,
                        extensions = extensionsState.value,
                        id = idState.value, onId = { idState.value = it },
                        name = nameState.value, onName = { nameState.value = it },
                        description = descriptionState.value, onDescription = { descriptionState.value = it },
                        source = sourceState.value, onSource = { sourceState.value = it },
                        tests = testsState.value, onTests = { testsState.value = it },
                        runInput = runInputState.value, onRunInput = { runInputState.value = it },
                        runOutput = runOutputState.value,
                        reports = reports.toMap(),
                        onBack = { finish() },
                        onRefresh = ::refresh,
                        onSaveDraft = ::saveDraft,
                        onTest = ::testExtension,
                        onEnable = { id -> confirmEnableState.value = id },
                        onDisable = ::disableExtension,
                        onRun = ::runExtension
                    )
                }
            }
        }
    }

    private fun refresh(message: String? = null) {
        runCatching { store.all() }
            .onSuccess {
                extensionsState.value = it
                statusState.value = message ?: "${it.size} saved extension(s) · ${it.count { e -> e.enabled && e.verified }} enabled and verified"
            }
            .onFailure {
                statusState.value = "Registry unavailable: ${it.message ?: it.javaClass.simpleName}. Existing data was not overwritten."
            }
    }

    private fun saveDraft() {
        val id = idState.value.trim()
        val name = nameState.value.trim()
        val source = sourceState.value
        val invalid = SelfExtendingExtensionPolicy.validateIdentity(id, name)
            ?: SelfExtendingExtensionPolicy.validateSource("python", source)
        if (invalid != null) {
            statusState.value = invalid
            return
        }
        val extension = GeneratedExtension(
            id = id, displayName = name, runtime = "python", source = source,
            sourceSha256 = SelfExtendingExtensionPolicy.sha256(source),
            description = descriptionState.value.trim().take(240),
            createdAtMillis = System.currentTimeMillis()
        )
        if (store.saveDraft(extension)) {
            reports.remove(id)
            idState.value = ""; nameState.value = ""; descriptionState.value = ""
            statusState.value = "Draft saved disabled. Run sandbox tests before enabling it."
            refresh(statusState.value)
            Toast.makeText(this, "Extension draft saved", Toast.LENGTH_SHORT).show()
        } else {
            statusState.value = "Draft not saved. Check the ID, source, duplicate ID, and storage state."
        }
    }

    private fun testExtension(id: String) {
        val ext = runCatching { store.all().firstOrNull { it.id == id } }.getOrNull()
        if (ext == null) {
            statusState.value = "Draft not found or registry unavailable."
            return
        }
        val cases = try { JSONArray(testsState.value) } catch (e: Exception) {
            statusState.value = "Test cases must be a valid JSON array."
            return
        }
        val parsed = (0 until cases.length()).mapNotNull { cases.optJSONObject(it) }
        if (parsed.size != cases.length()) {
            statusState.value = "Each test case must be a JSON object with input and expect fields."
            return
        }
        Thread {
            val report = runCatching { sandbox.test(ext, parsed) }.getOrElse {
                ExtensionTestReport(false, "", "Sandbox test crashed safely: ${it.message ?: it.javaClass.simpleName}")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (report.passed) reports[id] = report else reports.remove(id)
                statusState.value = if (report.passed) "TEST PASSED · ${report.summary}" else "TEST FAILED · ${report.summary}"
                Toast.makeText(this, if (report.passed) "Sandbox tests passed" else "Sandbox tests failed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun disableExtension(id: String) {
        val ok = store.disable(id)
        if (ok) reports.remove(id)
        refresh(if (ok) "Extension disabled; its source remains saved." else "Could not disable extension.")
    }

    private fun runExtension(id: String) {
        val input = try { JSONObject(runInputState.value) } catch (_: Exception) {
            runOutputState.value = "Input must be a JSON object."
            return
        }
        Thread {
            val result = runCatching {
                val ext = store.enabled(id) ?: return@runCatching JSONObject().put("ok", false).put("error", "Extension is not enabled, verified, and hash-matching.")
                sandbox.execute(ext, input, emptySet())
            }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) runOutputState.value = result.toString(2).take(4000)
            }
        }.start()
    }

    @Composable
    private fun SelfExtensionLabScreen(
        status: String,
        extensions: List<GeneratedExtension>,
        id: String, onId: (String) -> Unit,
        name: String, onName: (String) -> Unit,
        description: String, onDescription: (String) -> Unit,
        source: String, onSource: (String) -> Unit,
        tests: String, onTests: (String) -> Unit,
        runInput: String, onRunInput: (String) -> Unit,
        runOutput: String,
        reports: Map<String, ExtensionTestReport>,
        onBack: () -> Unit, onRefresh: () -> Unit, onSaveDraft: () -> Unit,
        onTest: (String) -> Unit, onEnable: (String) -> Unit,
        onDisable: (String) -> Unit, onRun: (String) -> Unit
    ) {
        Column(
            Modifier.fillMaxSize().background(ink).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("‹  SOUL ENGINE", color = blue, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(Modifier))
                .also { }
            TextButton(onClick = onBack) { Text("← Back to Command Center", color = muted) }
            Text("SELF-EXTENSION LAB", color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text("Build a new skill", color = white, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Drafts are inert by default. Orion runs the constrained Python sandbox, checks expected results, and enables a skill only after a successful test and your explicit approval.", color = muted, fontSize = 12.sp, lineHeight = 18.sp)
            PanelCard {
                Text("CREATE DRAFT", color = white, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Field("Unique ID · lowercase", id, onId, "e.g. number_helper", singleLine = true)
                Field("Display name", name, onName, "e.g. Number helper", singleLine = true)
                Field("What should it do?", description, onDescription, "Short description", singleLine = false)
                Field("Python source · reads INPUT and sets RESULT", source, onSource, "RESULT = INPUT[\"value\"] * 2", singleLine = false, minLines = 4)
                Field("Sandbox tests · JSON array", tests, onTests, "[{\"input\": {\"value\": 3}, \"expect\": 6}]", singleLine = false, minLines = 3)
                Button(onClick = onSaveDraft, modifier = Modifier.fillMaxWidth()) { Text("Save disabled draft") }
            }
            PanelCard {
                Text("REGISTRY", color = white, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(status, color = muted, fontSize = 12.sp, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh) { Text("Refresh") }
                    Text("Python sandbox only · no filesystem, network, or shell access", color = muted, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
            }
            if (extensions.isEmpty()) {
                PanelCard { Text("No saved extensions yet. Create a small data-processing skill above, save it as a draft, and run its tests before enabling it.", color = muted, fontSize = 12.sp, lineHeight = 18.sp) }
            }
            extensions.forEach { ext ->
                PanelCard {
                    Text(ext.displayName, color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${ext.id} · ${ext.runtime} · ${if (ext.enabled && ext.verified) "ENABLED / VERIFIED" else "DRAFT / DISABLED"}", color = if (ext.enabled && ext.verified) blue else muted, fontSize = 10.sp)
                    Text(ext.description.ifBlank { "No description provided." }, color = muted, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Source hash: ${ext.sourceSha256.take(12)}", color = muted, fontSize = 10.sp)
                    reports[ext.id]?.let { Text("Last test: ${it.summary}", color = if (it.passed) blue else muted, fontSize = 11.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onTest(ext.id) }) { Text("Test") }
                        if (ext.enabled && ext.verified) {
                            Button(onClick = { onRun(ext.id) }) { Text("Run") }
                            TextButton(onClick = { onDisable(ext.id) }) { Text("Disable", color = muted) }
                        } else {
                            TextButton(onClick = { onEnable(ext.id) }, enabled = reports[ext.id]?.passed == true) { Text("Enable", color = blue) }
                        }
                    }
                }
            }
            if (extensions.any { it.enabled && it.verified }) {
                PanelCard {
                    Text("RUN INPUT", color = white, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Field("JSON object", runInput, onRunInput, "{\"value\": 5}", singleLine = false, minLines = 2)
                    if (runOutput.isNotBlank()) Text(runOutput, color = white, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        val idToConfirm = confirmEnableState.value
        if (idToConfirm != null) {
            AlertDialog(
                onDismissRequest = { confirmEnableState.value = null },
                title = { Text("Enable this extension?") },
                text = { Text("Only the exact source hash that passed the sandbox test will be promoted. Enabling makes this data-processing skill available to Orion; it does not grant phone, network, file, or shell access.") },
                confirmButton = {
                    TextButton(onClick = {
                        val report = reports[idToConfirm]
                        val ok = report != null && store.promote(idToConfirm, report, userApproved = true)
                        confirmEnableState.value = null
                        refresh(if (ok) "Extension enabled after test and approval." else "Enable failed: run a successful test on this exact draft first.")
                    }) { Text("Approve & enable") }
                },
                dismissButton = { TextButton(onClick = { confirmEnableState.value = null }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    private fun PanelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp))
                .border(1.dp, line, RoundedCornerShape(18.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = content
        )
    }

    @Composable
    private fun Field(label: String, value: String, onValue: (String) -> Unit, hint: String, singleLine: Boolean, minLines: Int = 1) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value, onValueChange = onValue, placeholder = { Text(hint, color = muted) },
                modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines,
                textStyle = androidx.compose.ui.text.TextStyle(color = white, fontSize = 12.sp)
            )
        }
    }
}
