package com.phoneai.app

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.media.session.MediaSessionManager
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
        // Seed currently visible notifications as well as future post events.
        try { activeNotifications?.forEach { capture(it) } } catch (_: Exception) { }
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
            synchronized(lock) { recent.clear() }
        }
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

        private fun safeMediaControllers(): Pair<OrionNotificationListener?, List<MediaController>> {
            val service = instance ?: return null to emptyList()
            return try {
                val manager = service.getSystemService(MediaSessionManager::class.java)
                    ?: return service to emptyList()
                val sessions = manager.getActiveSessions(ComponentName(service, OrionNotificationListener::class.java))
                    .filterNot { SafetyGate.isSensitiveApp(it.packageName, it.packageName) }
                service to sessions
            } catch (_: SecurityException) {
                service to emptyList()
            } catch (_: Exception) {
                service to emptyList()
            }
        }

        fun mediaStatus(): String {
            val (service, sessions) = safeMediaControllers()
            if (service == null) return "Notification access is required for media-session status. Enable Orion under Android Notification access settings."
            if (sessions.isEmpty()) return "No controllable, non-sensitive media session is active. Start playback in a media app and ensure Orion has Notification access."
            return sessions.take(5).mapIndexed { index, controller ->
                val metadata = controller.metadata
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Unknown title" }
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                val state = when (controller.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> "playing"
                    PlaybackState.STATE_PAUSED -> "paused"
                    PlaybackState.STATE_BUFFERING -> "buffering"
                    PlaybackState.STATE_STOPPED -> "stopped"
                    else -> "idle or unknown"
                }
                val artistText = if (artist.isNotBlank()) " — $artist" else ""
                "${index + 1}. ${controller.packageName}: $title$artistText [$state]"
            }.joinToString("\n")
        }

        fun controlMedia(action: String): String {
            val normalized = action.trim().lowercase()
            val allowed = setOf("play", "pause", "play_pause", "next", "previous", "stop")
            if (normalized !in allowed) return "error: unsupported media action. Use play, pause, play_pause, next, previous, or stop."
            val (service, sessions) = safeMediaControllers()
            if (service == null) return "error: notification access is required for media controls. Enable Orion under Android Notification access settings."
            val controller = sessions.firstOrNull()
                ?: return "error: no controllable, non-sensitive media session is active."
            return try {
                val controls = controller.transportControls
                when (normalized) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "play_pause" -> {
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                    }
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    "stop" -> controls.stop()
                }
                "Sent '$normalized' to ${controller.packageName}. Check media_status to confirm the playback state; a command being sent does not guarantee the player accepted it."
            } catch (e: SecurityException) {
                "error: Android denied this media control. Confirm Orion's Notification access is enabled."
            } catch (e: Exception) {
                "error: media control failed (" + (e.message ?: e.javaClass.simpleName) + ")"
            }
        }

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
