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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        try {
            setContent {
                    SoulEngineComposeScreen(
                        status = statusState.value,
                        metrics = metricsState.value,
                        selfTest = selfTestState.value,
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
                        onOpenTeach = { open(TeachModeActivity::class.java) }
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
            val tools = runCatching { ToolRegistry.all.size }.getOrDefault(0)
            val packs = runCatching { AgentPolicy.packs.count { it.implemented } }.getOrDefault(0)
            val enabled = runCatching { AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(this, it.id) } }.getOrDefault(0)
            val providerKeys = runCatching { Providers.all.count { Prefs.hasKey(this, it.id) } }.getOrDefault(0)
            val services = runCatching { OrionConnectionStore.list(this) }.getOrDefault(emptyList())
            val verified = services.count { it.secretAvailable && it.verified }
            val savedTasks = runCatching { TaskStore.all(this).size }.getOrDefault(0)
            val memoryCount = runCatching { MemoryStore.all(this).size }.getOrDefault(0)
            val knowledgeCount = runCatching { KnowledgeStore.list(this).size }.getOrDefault(0)
            val serviceReady = PhoneAiService.instance != null
            val shellReady = runCatching { TermuxBridgeRunner.readiness(this) == null }.getOrDefault(false)
            val status = when {
                tools == 0 || packs == 0 -> "BLOCKED · core registry unavailable"
                !serviceReady || enabled == 0 -> "LIMITED · service or capability packs need attention"
                else -> "READY FOR CHECKS · live workflows still require testing"
            }
            statusState.value = status
            metricsState.value = buildString {
                appendLine("$tools registered tools")
                appendLine("$enabled / $packs implemented capability packs enabled")
                appendLine("$providerKeys provider keys saved (saved does not mean verified)")
                appendLine("$verified / ${services.count { it.secretAvailable }} saved service connections verified")
                appendLine("$savedTasks saved tasks · $memoryCount memories · $knowledgeCount knowledge documents")
                append("Phone service: ${if (serviceReady) "connected" else "not connected"} · shell: ${if (shellReady) "ready" else "limited"}")
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
