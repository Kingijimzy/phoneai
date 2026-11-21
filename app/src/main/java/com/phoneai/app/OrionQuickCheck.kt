package com.phoneai.app

import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-tap health check across everything Orion depends on: providers (with measured first-word speed), voice, vision,
 * memory, the internet, the phone permissions it needs, and the platforms it connects to. Each line is measured or read
 * from real state, never assumed; WARN means it works but is degraded (slow, single route), FAIL means it will not work.
 */
internal object OrionQuickCheck {
    enum class Level { PASS, WARN, FAIL }
    data class Row(val group: String, val name: String, val level: Level, val note: String, val ms: Long = 0)

    /** First-word time above this is called slow: the owner waits and Orion feels dead. */
    const val SLOW_FIRST_WORD_MS = 15_000.0

    /** Pure: how a provider's measured first-word time reads. */
    fun speedRow(label: String, firstTokenMs: Double?): Row = when {
        firstTokenMs == null -> Row("Providers", "$label speed", Level.WARN, "no timing measured yet; it will be after a few replies")
        firstTokenMs > SLOW_FIRST_WORD_MS -> Row("Providers", "$label speed", Level.WARN, "first word takes ${(firstTokenMs / 1000).toInt()}s on average; slow, prefer another route")
        else -> Row("Providers", "$label speed", Level.PASS, "first word in ${"%.1f".format(firstTokenMs / 1000)}s")
    }

    /** Pure: how many routes can serve a reply, and whether that is a single point of failure. */
    fun failoverRow(ready: Int, down: List<String>): Row = when {
        ready == 0 -> Row("Providers", "providers", Level.FAIL, "no provider has a key; Orion cannot think. Add one in Settings.")
        down.isNotEmpty() -> Row("Providers", "providers", Level.WARN, "${down.joinToString()} is DOWN; $ready configured")
        ready == 1 -> Row("Providers", "providers", Level.WARN, "only one provider; nothing to fail over to")
        else -> Row("Providers", "providers", Level.PASS, "$ready configured, none down")
    }

    fun summary(rows: List<Row>): String {
        val fail = rows.count { it.level == Level.FAIL }; val warn = rows.count { it.level == Level.WARN }
        return when { fail > 0 -> "$fail failing, $warn degraded"; warn > 0 -> "working, $warn degraded"; else -> "all ${rows.size} checks passed" }
    }

    fun render(rows: List<Row>): String = "QUICK CHECK: " + summary(rows) + "\n" + rows.joinToString("\n") { "${it.level.name.padEnd(4)} ${it.name.padEnd(20)} ${it.note}" }

    private inline fun probe(group: String, name: String, block: () -> Pair<Level, String>): Row {
        val t = System.currentTimeMillis()
        val (level, note) = try { block() } catch (e: Throwable) { Level.FAIL to (e.message ?: e.javaClass.simpleName) }
        return Row(group, name, level, note.replace('\n', ' ').take(120), System.currentTimeMillis() - t)
    }

    /** Runs every probe that needs only a Context. Blocking (network probes): call off the main thread. */
    fun run(c: Context): List<Row> {
        val rows = ArrayList<Row>()
        val configured = Providers.all.filter { Prefs.hasKey(c, it.id) || it.noKey }
        val health = OrionProviderHealth.all(c)
        rows += failoverRow(configured.size, health.values.filter { it.state == "DOWN" }.map { Providers.find(it.provider)?.label ?: it.provider })
        val profiles = runCatching { OrionModelRegistry.allProfiles(c) }.getOrDefault(emptyMap())
        configured.take(4).forEach { p -> rows += speedRow(p.label, OrionModelRegistry.firstTokenFor(profiles, p.id, Prefs.model(c, p, false), 2)) }

        rows += probe("Senses", "internet") {
            val conn = URL("https://example.com").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.requestMethod = "HEAD"
            val code = conn.responseCode; conn.disconnect()
            (if (code in 100..599) Level.PASS else Level.WARN) to "reached the internet (HTTP $code)"
        }
        rows += probe("Senses", "voice") {
            val neural = Prefs.neuralVoice(c) && NeuralVoiceInstaller.isInstalled(c)
            if (neural) Level.PASS to "neural voice installed" else Level.WARN to "using the Android voice; install the neural voice for a natural one"
        }
        rows += probe("Senses", "vision") {
            val vision = configured.any { ProviderModelCapabilityPolicy.isChatCapable(Prefs.model(c, it, false)) }
            if (vision) Level.PASS to "look {question, path} sends images to your chat provider's vision model" else Level.FAIL to "no chat-capable model configured"
        }
        rows += probe("Senses", "memory") {
            val n = MemoryStore.all(c).size
            if (n > 0) Level.PASS to "$n memories stored" else Level.WARN to "memory is empty; Orion knows nothing about you yet"
        }
        rows += probe("Phone", "accessibility") {
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any { it.substringBefore('/').equals(c.packageName, true) } == true
            if (on) Level.PASS to "screen reading and taps are on" else Level.WARN to "off: Orion cannot read or tap the screen"
        }
        rows += probe("Phone", "notifications") {
            val on = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
            if (on) Level.PASS to "notification access granted" else Level.WARN to "no notification access"
        }
        rows += probe("Platforms", "GitHub") {
            if (OrionConnectionStore.tokenForConnector(c, "github").isBlank()) Level.WARN to "not connected (optional)" else Level.PASS to "token saved"
        }
        rows += probe("Platforms", "MCP server") {
            if (McpConnector.configured(c)) Level.PASS to "endpoint set: ${McpConnector.endpoint(c).take(40)}" else Level.WARN to "no MCP endpoint (optional)"
        }
        rows += probe("Platforms", "Termux") {
            val has = runCatching { c.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
            if (has) Level.PASS to "installed" else Level.WARN to "not installed (optional)"
        }
        return rows
    }
}
