package com.phoneai.app

import android.content.Context

/**
 * Android-native, consent-gated adaptation of Ultron Chronos.
 *
 * Accessibility window events are reduced immediately to a coarse category. Package names are
 * never persisted or returned to the model; sensitive app identifiers are discarded entirely.
 * This is awareness only: it never launches apps, changes device settings or schedules actions.
 */
internal object OrionChronosAwareness {
    private const val PREFS = "orion_chronos_awareness_v1"
    private const val KEY_CATEGORY = "category"
    private const val KEY_FOCUS_STARTED = "focus_started_at"
    private const val KEY_LAST_EVENT = "last_event_at"
    private const val TTL_MS = 2L * 60L * 60L * 1000L

    @Volatile private var lastPackageInProcess: String = ""

    @Synchronized fun onWindowChanged(
        context: Context,
        rawPackageName: String?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val app = context.applicationContext
        val appUsage = OrionSensorConsent.read(app, OrionSensorScope.APP_USAGE, now)
        val chronos = OrionSensorConsent.read(app, OrionSensorScope.CHRONOS_AWARENESS, now)
        if (!appUsage.active() || !chronos.active() || chronos.foregroundOnly) {
            clear(app)
            return false
        }

        val packageName = rawPackageName.orEmpty().trim().lowercase().take(180)
        if (packageName.isBlank() || isSensitive(packageName)) {
            clear(app)
            lastPackageInProcess = packageName
            return false
        }

        val category = categoryFor(packageName)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousCategory = prefs.getString(KEY_CATEGORY, "").orEmpty()
        val previousStarted = prefs.getLong(KEY_FOCUS_STARTED, 0L)
        val sameWindow = packageName == lastPackageInProcess
        val sameCategory = previousCategory == category
        val startedAt = if (sameWindow && sameCategory && previousStarted in 1L..now) {
            previousStarted
        } else now

        lastPackageInProcess = packageName
        return prefs.edit()
            .putString(KEY_CATEGORY, category)
            .putLong(KEY_FOCUS_STARTED, startedAt)
            .putLong(KEY_LAST_EVENT, now)
            .commit()
    }

    @Synchronized fun summary(context: Context, now: Long = System.currentTimeMillis()): String {
        val app = context.applicationContext
        val appUsage = OrionSensorConsent.read(app, OrionSensorScope.APP_USAGE, now)
        val chronos = OrionSensorConsent.read(app, OrionSensorScope.CHRONOS_AWARENESS, now)
        if (!appUsage.active() || !chronos.active() || chronos.foregroundOnly) {
            clear(app)
            return ""
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val category = prefs.getString(KEY_CATEGORY, "").orEmpty()
        val started = prefs.getLong(KEY_FOCUS_STARTED, 0L)
        val lastEvent = prefs.getLong(KEY_LAST_EVENT, 0L)
        if (category.isBlank() || started <= 0L || lastEvent <= 0L ||
            started > now || lastEvent > now || now - lastEvent > TTL_MS) {
            clear(app)
            return ""
        }
        val minutes = ((now - started).coerceAtLeast(0L) / 60_000L).coerceAtMost(120L)
        return "OWNER-GRANTED CHRONOS CONTEXT: current app category=$category; focus duration about $minutes minutes. Coarse category only; no app identifier or screen content retained."
    }

    @Synchronized fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        lastPackageInProcess = ""
    }

    private fun isSensitive(packageName: String): Boolean =
        SENSITIVE_MARKERS.any { packageName.contains(it) }

    private fun categoryFor(packageName: String): String = when {
        packageName.contains("whatsapp") || packageName.contains("telegram") ||
            packageName.contains("signal") || packageName.contains("messenger") ||
            packageName.contains("discord") -> "communication"
        packageName.contains("facebook") || packageName.contains("instagram") ||
            packageName.contains("tiktok") || packageName.contains("snapchat") ||
            packageName.contains("reddit") || packageName.contains("x.android") -> "social"
        packageName.contains("youtube") || packageName.contains("netflix") ||
            packageName.contains("spotify") || packageName.contains("music") -> "media"
        packageName.contains("chrome") || packageName.contains("browser") ||
            packageName.contains("firefox") -> "browsing"
        packageName.contains("maps") || packageName.contains("navigation") -> "navigation"
        packageName.contains("docs") || packageName.contains("office") ||
            packageName.contains("notion") || packageName.contains("editor") ||
            packageName.contains("code") -> "productivity"
        packageName.contains("shopping") || packageName.contains("amazon") ||
            packageName.contains("jumia") -> "shopping"
        packageName.contains("game") || packageName.contains("pubg") ||
            packageName.contains("mobilelegends") || packageName.contains("fc") -> "gaming"
        packageName == "com.phoneai.app" -> "orion"
        else -> "other"
    }

    private val SENSITIVE_MARKERS = setOf(
        "bank", "banking", "payment", "pay", "wallet", "bybit", "binance",
        "deriv", "crypto", "exchange", "authenticator", "password", "credential",
        "1password", "bitwarden", "keepass", "paypal", "stripe"
    )
}
