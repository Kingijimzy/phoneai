package com.phoneai.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Optional notification access. Safe summaries stay in memory only.
 * Financial apps and authentication/security-code notifications are discarded at ingest,
 * before titles or bodies can be exposed to the agent.
 */
class OrionNotificationListener : NotificationListenerService() {
    data class SafeNotification(
        val key: String,
        val whenMillis: Long,
        val app: String,
        val title: String,
        val text: String
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) { recent.remove(sbn.key) }
    }

    private fun capture(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName.orEmpty()
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            if (SafetyGate.isSensitiveApp(pkg, app)) {
                synchronized(lock) { recent.remove(sbn.key) }
                return
            }
            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                )?.toString().orEmpty().trim()
            val combined = "$title $text"
            if (title.isBlank() && text.isBlank()) return
            if (SafetyGate.containsSensitiveCredentialText(combined) || securityOrFinancialContent.containsMatchIn(combined)) {
                synchronized(lock) { recent.remove(sbn.key) }
                return
            }
            val item = SafeNotification(
                key = sbn.key,
                whenMillis = sbn.postTime,
                app = app.take(60),
                title = title.take(180),
                text = text.take(500)
            )
            synchronized(lock) {
                recent[sbn.key] = item
                while (recent.size > 20) recent.remove(recent.keys.first())
            }
        } catch (_: Exception) {
            // Fail closed: notification content is never logged or forwarded on parsing errors.
        }
    }

    companion object {
        @Volatile private var instance: OrionNotificationListener? = null
        private val lock = Any()
        private val recent = LinkedHashMap<String, SafeNotification>()
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val securityOrFinancialContent = Regex(
            "(?i)\\b(bank|banking|payment|paid|transfer|transaction|account\\s+balance|available\\s+balance|card\\s+ending|verification\\s+code|authentication|authenticator|security\\s+alert|sign[- ]in\\s+code|login\\s+code|one[- ]time\\s+code|one[- ]time\\s+password|2fa|two[- ]factor|withdrawal|deposit|statement)\\b"
        )

        fun readSafeNotifications(): String {
            if (instance == null) {
                return "Notification access is not enabled. Open Android Notification access settings and enable Orion, then retry. Orion excludes banking, payment, password, card, CVV, OTP, and security-code notifications."
            }
            val items = synchronized(lock) { recent.values.toList().sortedByDescending { it.whenMillis } }
            if (items.isEmpty()) {
                return "No safe notifications are available. Banking/payment and authentication/security notifications are deliberately excluded; access is memory-only."
            }
            return "Recent safe notifications (memory-only; sensitive financial/security notifications filtered):\n" +
                items.joinToString("\n\n") {
                    val time = try { timeFormat.format(Date(it.whenMillis)) } catch (_: Exception) { "--:--" }
                    "$time · ${it.app}\n${it.title}\n${it.text}".trim()
                }.take(5000)
        }
    }
}
