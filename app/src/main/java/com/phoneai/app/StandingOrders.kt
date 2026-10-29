package com.phoneai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A recurring, read-only check Orion runs by itself while the app service is alive. */
data class StandingOrder(val kind: String, val everyMinutes: Int, val lastRunAt: Long, val lastDigest: String)

internal object StandingOrderPolicy {
    val kinds = mapOf(
        "gmail_unread" to "new unread Gmail",
        "github_notifications" to "new GitHub notifications",
        "brief" to "the live brief (weather, team, prices, tasks)"
    )
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 7 * 24 * 60
    const val MAX_ORDERS = 6

    fun validate(kind: String, minutes: Int): String? = when {
        kind !in kinds -> "kind must be one of: " + kinds.keys.joinToString()
        minutes < MIN_MINUTES -> "minimum interval is $MIN_MINUTES minutes (saves battery and quota)"
        minutes > MAX_MINUTES -> "maximum interval is one week"
        else -> null
    }

    fun due(o: StandingOrder, now: Long) = now - o.lastRunAt >= o.everyMinutes * 60_000L

    /** Only tell the user when the result actually changed and is not an empty/error state. */
    fun shouldNotify(previousDigest: String, result: String, kind: String = "", dayKey: String = ""): Boolean {
        val normalized = result.trim()
        if (normalized.isEmpty() || normalized.startsWith("error", ignoreCase = true)) return false
        if (normalized.startsWith("No unread", ignoreCase = true) ||
            normalized.startsWith("Nothing new", ignoreCase = true) ||
            normalized.endsWith("(empty result)", ignoreCase = true)) return false
        // Connector replies carry a header line; an empty list means nothing to say.
        if (Regex("(?im)^\\s*0 matching message\\(s\\)\\.\\s*$").containsMatchIn(normalized)) return false
        return digestFor(kind, normalized, dayKey) != previousDigest
    }

    /** Stable, collision-resistant fingerprint; String.hashCode can collide on distinct results. */
    fun digest(result: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(result.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The brief contains live prices, so it would differ on every run; alert at most once per calendar day. */
    fun digestFor(kind: String, result: String, dayKey: String): String =
        if (kind == "brief") "day:$dayKey" else digest(result)

    /** One readable notification line without the connector's safety header. */
    fun headline(result: String): String {
        val lines = result.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.contains("untrusted") || it.contains("read-only") || it.startsWith("BRIEF") }.toList()
        return lines.take(3).joinToString(" · ").take(160).ifBlank { "Something new" }
    }

    /** Do not consume a new-result digest when Android could not post its notification. */
    fun digestAfterAttempt(previousDigest: String, result: String, notificationNeeded: Boolean, notificationPosted: Boolean, kind: String = "", dayKey: String = ""): String =
        if (result.startsWith("error") || (notificationNeeded && !notificationPosted)) previousDigest else digestFor(kind, result, dayKey)
}

object StandingOrders {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_standing_orders.json")

    fun all(c: Context): List<StandingOrder> = synchronized(lock) {
        try {
            if (!file(c).exists()) return@synchronized emptyList()
            val a = JSONArray(file(c).readText())
            (0 until a.length()).map { val o = a.getJSONObject(it); StandingOrder(o.optString("kind"), o.optInt("every", 60), o.optLong("last"), o.optString("digest")) }
        } catch (_: Exception) { emptyList() }
    }

    /** Atomic persistence: callers must not report success if the durable write failed. */
    private fun save(c: Context, list: List<StandingOrder>): Boolean {
        val atomic = AtomicFile(file(c))
        var stream: java.io.FileOutputStream? = null
        return try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("kind", it.kind).put("every", it.everyMinutes).put("last", it.lastRunAt).put("digest", it.lastDigest)) }
            stream = atomic.startWrite()
            stream.write(a.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) try { atomic.failWrite(stream) } catch (_: Exception) { }
            false
        }
    }

    fun add(c: Context, kind: String, minutes: Int): String = synchronized(lock) {
        StandingOrderPolicy.validate(kind, minutes)?.let { return@synchronized "error: $it" }
        val list = all(c).filter { it.kind != kind }
        if (list.size >= StandingOrderPolicy.MAX_ORDERS) return@synchronized "error: at most ${StandingOrderPolicy.MAX_ORDERS} standing orders"
        if (!save(c, list + StandingOrder(kind, minutes, 0L, ""))) {
            return@synchronized "error: standing order could not be saved to device storage; no order was added"
        }
        "standing order saved: I'll check ${StandingOrderPolicy.kinds[kind]} every $minutes min while Orion is running, and notify you only when something new appears."
    }

    fun remove(c: Context, kind: String): String = synchronized(lock) {
        val list = all(c); if (list.none { it.kind == kind }) return@synchronized "error: no standing order '$kind'"
        if (!save(c, list.filter { it.kind != kind })) return@synchronized "error: standing order could not be removed because device storage failed"
        "removed standing order '$kind'"
    }

    fun describe(c: Context): String {
        val list = all(c)
        if (list.isEmpty()) return "no standing orders yet"
        return list.joinToString("\n") { "- ${it.kind}: every ${it.everyMinutes} min" }
    }

    internal fun update(c: Context, kind: String, digest: String): Boolean = synchronized(lock) {
        val current = all(c)
        if (current.none { it.kind == kind }) return@synchronized false
        save(c, current.map { if (it.kind == kind) it.copy(lastRunAt = System.currentTimeMillis(), lastDigest = digest) else it })
    }
}

/** The "alive" loop: called every few minutes by the service; runs due standing orders without any AI call. */
object LifeLoop {
    private const val CHANNEL = "orion_life"

    /** Blocking; call off the main thread. Returns how many notifications were posted. */
    fun tick(c: Context): Int {
        var posted = 0
        val now = System.currentTimeMillis()
        for (o in StandingOrders.all(c)) {
            if (!StandingOrderPolicy.due(o, now)) continue
            val result = try {
                when (o.kind) {
                    "gmail_unread" -> GmailConnector.read(c, "unread", 3)
                    "github_notifications" -> GithubConnector.get(c, "/notifications")
                    "brief" -> MorningBrief.build(c)
                    else -> "error: unknown order"
                }
            } catch (e: Exception) { "error: " + (e.message ?: "failed") }
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val notify = StandingOrderPolicy.shouldNotify(o.lastDigest, result, o.kind, dayKey)
            val notificationPosted = notify && post(c, o.kind, result)
            val nextDigest = StandingOrderPolicy.digestAfterAttempt(
                o.lastDigest, result, notify, notificationPosted, o.kind, dayKey
            )
            val persisted = StandingOrders.update(c, o.kind, nextDigest)
            if (notificationPosted) posted++
            AgentPolicy.log(c, "standing_order", o.kind, null, when {
                !persisted -> "result checked but schedule/digest could not be persisted; may retry after restart"
                notificationPosted -> "notified"
                notify -> "notification not posted; digest retained for retry"
                else -> "nothing new or error"
            })
        }
        return posted
    }

    private fun post(c: Context, kind: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Orion standing orders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 84021, c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CHANNEL) else android.app.Notification.Builder(c)
        nm.notify(84100 + kind.hashCode().and(0xff), b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion: " + (StandingOrderPolicy.kinds[kind] ?: kind))
            .setContentText(StandingOrderPolicy.headline(text)).setStyle(android.app.Notification.BigTextStyle().bigText(text.take(900)))
            .setContentIntent(open).setAutoCancel(true).build())
        return true
    }
}
