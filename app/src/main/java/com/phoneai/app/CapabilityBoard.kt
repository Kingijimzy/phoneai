package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something the user asked for that Orion could not do yet, kept so it can be fixed and offered again later. */
data class CapabilityGap(val goal: String, val provider: String, val status: String, val count: Int, val lastAt: Long)

internal object CapabilityBoardPolicy {
    const val MAX = 30
    /** Statuses: requested (asked, not possible), proposed (a plan was shown), connected (resolved). */
    fun record(list: List<CapabilityGap>, goal: String, provider: String, now: Long): List<CapabilityGap> {
        val g = goal.trim().replace(Regex("\\s+"), " ").take(140)
        if (g.length < 3) return list
        val out = list.toMutableList()
        val i = out.indexOfFirst { it.provider == provider && MemoryRanking.similarity(it.goal, g) >= 0.5 }
        if (i >= 0) out[i] = out[i].copy(goal = g, count = out[i].count + 1, lastAt = now, status = if (out[i].status == "connected") "requested" else out[i].status)
        else out += CapabilityGap(g, provider.take(40), "requested", 1, now)
        return out.sortedByDescending { it.lastAt }.take(MAX)
    }

    fun markResolved(list: List<CapabilityGap>, provider: String): List<CapabilityGap> =
        list.map { if (it.provider == provider && it.status != "connected") it.copy(status = "connected") else it }

    fun open(list: List<CapabilityGap>) = list.filter { it.status != "connected" }
}

object CapabilityBoard {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_capability_board.json")
    private fun load(c: Context): List<CapabilityGap> = try {
        val f = file(c)
        if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a ->
            (0 until a.length()).map { val o = a.getJSONObject(it); CapabilityGap(o.optString("g"), o.optString("p"), o.optString("s", "requested"), o.optInt("n", 1), o.optLong("t")) }
        }
    } catch (_: Exception) { emptyList() }
    private fun save(c: Context, l: List<CapabilityGap>) {
        try {
            val a = JSONArray(); l.forEach { a.put(JSONObject().put("g", it.goal).put("p", it.provider).put("s", it.status).put("n", it.count).put("t", it.lastAt)) }
            val tmp = File(c.filesDir, "orion_capability_board.json.tmp"); tmp.writeText(a.toString())
            if (!tmp.renameTo(file(c))) { file(c).writeText(a.toString()); tmp.delete() }
        } catch (_: Exception) { }
    }
    fun note(c: Context, goal: String, provider: String): Boolean = synchronized(lock) {
        if (SafetyGate.containsSensitiveCredentialText(goal)) return@synchronized false
        val before = load(c); val after = CapabilityBoardPolicy.record(before, goal, provider, System.currentTimeMillis())
        save(c, after); after !== before
    }
    fun resolved(c: Context, provider: String) = synchronized(lock) { save(c, CapabilityBoardPolicy.markResolved(load(c), provider)) }
    fun open(c: Context): List<CapabilityGap> = synchronized(lock) { CapabilityBoardPolicy.open(load(c)) }
    fun all(c: Context): List<CapabilityGap> = synchronized(lock) { load(c) }
}

/** What the soul loop should do after looking at the world once. Pure, so it is unit-testable. */
internal object SoulPolicy {
    const val TICK_MS = 20 * 60_000L
    fun due(lastAt: Long, now: Long) = now - lastAt >= TICK_MS
    /** Tell the user only when unread mail grew; never repeat the same count. */
    fun mailAlert(prevUnseen: Int, nowUnseen: Int): Boolean = nowUnseen > 0 && nowUnseen > prevUnseen
    fun mailText(unseen: Int, newest: ImapParse.Header?): String =
        "$unseen unread email(s)" + (newest?.let { " - latest from ${it.from.take(40)}: ${it.subject.take(60)}" } ?: "")
}

/** Event-driven look at connected services. No AI call; notifies only on something new. */
internal object SoulWatch {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("soul_watch_enabled", false)
    fun setEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("soul_watch_enabled", v).apply() }
    fun lastTick(c: Context) = sp(c).getLong("soul_watch_last", 0L)

    /**
     * One watcher tick, run by the background runner. Standing orders are checked on every tick (each has its own
     * interval). The Gmail/GitHub checks run at most every SoulPolicy interval and only when the user switched them on.
     * Each check is isolated: one broken account never silences the others or the standing orders.
     * Throws only after everything else has run, so the runtime can back off and retry the connector checks.
     */
    fun run(c: Context): String {
        val now = System.currentTimeMillis()
        val notes = ArrayList<String>()
        try { val posted = LifeLoop.tick(c); if (posted > 0) notes += "standing_orders:$posted" } catch (_: Exception) { }
        if (!SoulPolicy.due(lastTick(c), now)) return notes.joinToString(",").ifBlank { "not_due" }
        if (!enabled(c)) {
            sp(c).edit().putLong("soul_watch_last", now).apply()
            return notes.joinToString(",").ifBlank { "soul_watch_off" }
        }
        var failure: Exception? = null
        try { checkMail(c)?.let { notes += it } } catch (e: Exception) { failure = e }
        try { checkGithub(c)?.let { notes += it } } catch (e: Exception) { if (failure == null) failure = e }
        // Commit the due marker only after the connector checks finished, so failed checks are retried by backoff.
        sp(c).edit().putLong("soul_watch_last", now).apply()
        failure?.let { throw it }
        return notes.joinToString(",").ifBlank { "nothing_new" }
    }

    private fun checkMail(c: Context): String? {
        if (OrionConnectionStore.get(c, "gmail")?.secretAvailable != true || !AgentPolicy.enabled(c, "connected_services")) return null
        val r = GmailConnector.fetch(c, "unread", 1)
        if (r.ok) {
            val prev = sp(c).getInt("soul_mail_unseen", 0)
            var note: String? = null
            if (SoulPolicy.mailAlert(prev, r.unseen)) { post(c, 84031, "Orion: new mail", SoulPolicy.mailText(r.unseen, r.headers.firstOrNull())); note = "mail_alert" }
            sp(c).edit().putInt("soul_mail_unseen", r.unseen).apply()
            return note
        }
        if (r.error.contains("rejected")) {
            if (!sp(c).getBoolean("soul_mail_bad_notified", false)) { post(c, 84032, "Orion: Gmail needs reconnecting", "The app password was rejected. Open Orion > Capability board to reconnect."); sp(c).edit().putBoolean("soul_mail_bad_notified", true).apply() }
            return "mail_login_failed"
        }
        throw IllegalStateException("mail_unreachable")
    }

    private fun checkGithub(c: Context): String? {
        if (OrionConnectionStore.get(c, "github")?.secretAvailable != true || !AgentPolicy.enabled(c, "connected_services")) return null
        val out = GithubConnector.get(c, "/notifications")
        if (!out.startsWith("GITHUB DATA")) return null
        val n = out.lines().drop(1).count { it.isNotBlank() && !it.startsWith("(empty") }
        val prev = sp(c).getInt("soul_gh_notes", 0)
        var note: String? = null
        if (n > prev) { post(c, 84033, "Orion: GitHub activity", "$n unread GitHub notification(s)"); note = "github_alert" }
        sp(c).edit().putInt("soul_gh_notes", n).apply()
        return note
    }

    private fun post(c: Context, id: Int, title: String, text: String) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("orion_soul", "Orion soul alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            if (android.os.Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val open = android.app.PendingIntent.getActivity(c, id, android.content.Intent(c, CapabilityBoardActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, "orion_soul") else android.app.Notification.Builder(c)
            nm.notify(id, b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) { }
    }
}
