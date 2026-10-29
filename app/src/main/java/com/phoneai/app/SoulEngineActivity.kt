package com.phoneai.app

import android.app.Activity
import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf

/**
 * Native Compose command center. It reuses Orion's real registries and stores for the
 * readiness snapshot; UI labels never imply that an unverified workflow is live.
 */
class SoulEngineActivity : ComponentActivity() {
    private val statusState = mutableStateOf("Checking local readiness…")
    private val metricsState = mutableStateOf("")
    private val selfTestState = mutableStateOf("")
    private val coreEnabledState = mutableStateOf(false)
    private val proposalsState = mutableStateOf<List<SoulEngineState.Proposal>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        coreEnabledState.value = AgentPolicy.enabled(this, "orion_core")
        try {
            setContent {
                    SoulEngineComposeScreen(
                        status = statusState.value,
                        metrics = metricsState.value,
                        selfTest = selfTestState.value,
                        coreEnabled = coreEnabledState.value,
                        onCoreEnabledChange = { enabled ->
                            AgentPolicy.setEnabled(this@SoulEngineActivity, "orion_core", enabled)
                            coreEnabledState.value = AgentPolicy.enabled(this@SoulEngineActivity, "orion_core")
                            refreshSnapshot()
                            Toast.makeText(this@SoulEngineActivity, if (coreEnabledState.value) "Soul Engine core enabled" else "Soul Engine core paused", Toast.LENGTH_SHORT).show()
                        },
                        onRefresh = {
                            refreshSnapshot()
                            Toast.makeText(this@SoulEngineActivity, "Local status refreshed", Toast.LENGTH_SHORT).show()
                        },
                        onSelfTest = ::runSelfTest,
                        onOpenChat = { open(MainChatActivity::class.java) },
                        onOpenTasks = { open(TaskCenterActivity::class.java) },
                        onOpenAgents = { open(AgentsActivity::class.java) },
                        onOpenMemory = { open(MemoryActivity::class.java) },
                        onOpenTrading = { open(TradingActivity::class.java) },
                        onOpenBrowser = { startActivity(Intent(this@SoulEngineActivity, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/")) },
                        onOpenSettings = { startActivity(Intent(this@SoulEngineActivity, SettingsActivity::class.java)) },
                        onOpenCapabilityReport = { open(OrionCapabilityReportActivity::class.java) },
                        onOpenTeach = { open(TeachModeActivity::class.java) },
                        onOpenExtensions = { open(SelfExtensionLabActivity::class.java) },
                        proposals = proposalsState.value,
                        onReviewProposal = { id, status, title ->
                            val saved = runCatching { SoulEngineState.reviewProposal(this@SoulEngineActivity, id, status, title) }.getOrDefault(false)
                            if (saved) {
                                refreshSnapshot()
                                Toast.makeText(this@SoulEngineActivity, "Proposal ${status.name.lowercase()}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@SoulEngineActivity, "Proposal could not be saved; refresh and try again", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
            }
            refreshSnapshot()
        } catch (error: Exception) {
            Log.e(TAG, "Soul Engine Compose dashboard failed", error)
            statusState.value = "LIMITED · dashboard recovery required"
            metricsState.value = "The dashboard could not load one of its local data sources. Return to the app and retry. Error: " +
                (error.message ?: error.javaClass.simpleName).take(500)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
    }

    private fun open(activity: Class<out Activity>) {
        runCatching { startActivity(Intent(this, activity)) }
            .onFailure {
                Log.e(TAG, "Could not open Soul Engine workspace: " + activity.simpleName, it)
                Toast.makeText(this, "Could not open " + activity.simpleName + ": " + (it.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
    }

    private fun refreshSnapshot() {
        try {
            coreEnabledState.value = AgentPolicy.enabled(this, "orion_core")
            val tools = runCatching { ToolRegistry.all.size }.getOrDefault(0)
            val packs = runCatching { AgentPolicy.packs.count { it.implemented } }.getOrDefault(0)
            val enabled = runCatching { AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(this, it.id) } }.getOrDefault(0)
            val providerKeys = runCatching { Providers.all.count { Prefs.hasKey(this, it.id) } }.getOrDefault(0)
            val services = runCatching { OrionConnectionStore.list(this) }.getOrDefault(emptyList())
            val verified = services.count { it.secretAvailable && it.verified }
            val savedTasks = runCatching { TaskStore.all(this).size }.getOrDefault(0)
            val memoryCount = runCatching { MemoryStore.all(this).size }.getOrDefault(0)
            val knowledgeCount = runCatching { KnowledgeStore.list(this).size }.getOrDefault(0)
            val soulState = runCatching { SoulEngineState.snapshot(this) }.getOrNull()
            proposalsState.value = soulState?.proposals ?: emptyList()
            val pendingProposals = soulState?.proposals?.count { it.status == SoulEngineState.ProposalStatus.PENDING } ?: 0
            val lessonCount = soulState?.lessons?.size ?: 0
            val serviceReady = PhoneAiService.instance != null
            // The built-in shell is Orion-owned; its readiness must not depend on an optional
            // external terminal app or bridge being installed.
            val shellReady = runCatching {
                ShellRunner.workspace(this).isDirectory && java.io.File("/system/bin/sh").canExecute()
            }.getOrDefault(false)
            val status = when {
                tools == 0 || packs == 0 -> "BLOCKED · core registry unavailable"
                !coreEnabledState.value -> "PAUSED · Soul Engine core is switched off"
                !serviceReady -> "STOPPED · phone service is not connected"
                enabled == 0 -> "LIMITED · no implemented capability packs are enabled"
                else -> "MONITORING · service connected; actions remain policy-gated"
            }
            statusState.value = status
            metricsState.value = buildString {
                appendLine("$tools registered tools")
                appendLine("$enabled / $packs implemented capability packs enabled")
                appendLine("$providerKeys provider keys saved (saved does not mean verified)")
                appendLine("$verified / ${services.count { it.secretAvailable }} saved service connections verified")
                appendLine("$savedTasks saved tasks · $memoryCount memories · $knowledgeCount knowledge documents")
                appendLine("$pendingProposals learning proposals awaiting review · $lessonCount persisted lessons")
                appendLine("Active goal: ${soulState?.goal?.ifBlank { "none saved" } ?: "state unavailable"}")
                appendLine("Watcher: ${soulState?.watcherStatus?.ifBlank { "waiting for first tick" } ?: "state unavailable"} · ${soulState?.watcherAction?.ifBlank { "no action yet" } ?: "state unavailable"}")
                append("Phone service: ${if (serviceReady) "connected" else "not connected"} · shell: ${if (shellReady) "ready" else "limited"} · route learning: ${if (Prefs.routeLearning(this)) "enabled" else "off"}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Soul Engine live snapshot failed", error)
            statusState.value = "LIMITED · live status refresh failed"
            metricsState.value = "The dashboard is open, but a local status source failed: " +
                (error.message ?: error.javaClass.simpleName).take(300)
        }
    }

    private fun runSelfTest() {
        selfTestState.value = "Running local self-test…"
        Thread {
            val result = try {
                SelfTest.format(SelfTest.run(applicationContext))
            } catch (error: Exception) {
                "Self-test failed: " + (error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) selfTestState.value = result.take(2600)
            }
        }.start()
    }

    companion object {
        private const val TAG = "SoulEngineDashboard"
    }
}
