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
            if (!OrionSensorConsent.read(this, OrionSensorScope.NOTIFICATIONS).active()) {
                synchronized(lock) { recent.clear() }
                return
            }
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
            // Notify the live Soul session without retaining notification contents in its durable queue.
            // The listener has already excluded sensitive apps and financial/authentication content.
            val safeEventLabel = "notification from ${app.take(60)}"
            // Persist the event even when the accessibility service is not currently bound.
            // This stores no notification body, sender, title, or message text.
            runCatching {
                SoulEngineState.recordWatcherStatus(applicationContext, "event_received", safeEventLabel)
            }
            val liveService = PhoneAiService.instance
            if (liveService != null) {
                liveService.onExternalPresenceEvent(
                    eventKey = "notification:${pkg.take(80)}",
                    safeDescription = safeEventLabel
                )
            } else {
                // Keep the event durable when Orion's accessibility service is not bound.
                // WorkManager may notify the user that recovery is pending, but must not start
                // AccessibilityService or perform an unattended phone action.
                runCatching {
                    val runtime = OrionRuntime(applicationContext)
                    runtime.start()
                    runtime.enqueue(
                        OrionRuntime.WorkType.PRESENCE_EVENT,
                        "notification:${pkg.take(80)}:${sbn.postTime / 60_000L}",
                        3,
                        goal = "Record safe external event: $safeEventLabel",
                        checkpoint = "source=notification_listener; content=not_retained"
                    )
                    runtime.stop()
                }
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

        /** Act only on a currently active notification already admitted to the safe in-memory list. */
        fun notificationAction(key: String, action: String, replyText: String = ""): String {
            val normalized = action.trim().lowercase()
            if (key.isBlank()) return "error: notification key is required"
            if (normalized !in setOf("reply", "dismiss")) return "error: action must be reply or dismiss"
            val service = instance ?: return "error: enable Orion Notification access first"
            val allowed = synchronized(lock) { recent.containsKey(key) }
            if (!allowed) return "error: notification is unavailable or was filtered for safety"
            val sbn = try { service.activeNotifications?.firstOrNull { it.key == key } }
                catch (_: Exception) { null }
                ?: return "error: notification is no longer active"
            if (normalized == "dismiss") {
                return try {
                    service.cancelNotification(key)
                    synchronized(lock) { recent.remove(key) }
                    "dismiss request sent for the selected safe notification"
                } catch (e: Exception) { "error: could not dismiss notification (" + e.javaClass.simpleName + ")" }
            }
            if (replyText.isBlank()) return "error: reply text is required"
            if (replyText.length > 1000) return "error: reply is limited to 1000 characters"
            val actions = sbn.notification.actions ?: emptyArray()
            val candidate = actions.firstOrNull { action ->
                val inputs = action.remoteInputs
                !inputs.isNullOrEmpty() && inputs.any { it.allowFreeFormInput || it.choices?.isNotEmpty() == true }
            } ?: return "error: this notification has no supported direct-reply action; open the app and reply manually"
            val remoteInputs = candidate.remoteInputs ?: return "error: no reply input is available"
            val freeForm = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.firstOrNull()
                ?: return "error: no reply input is available"
            return try {
                val fillIn = android.content.Intent()
                val results = android.os.Bundle().apply { putCharSequence(freeForm.resultKey, replyText) }
                android.app.RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                candidate.actionIntent.send(service, 0, fillIn)
                "reply action sent to the selected safe notification; verify delivery in the source app"
            } catch (e: Exception) {
                "error: notification reply failed (" + e.javaClass.simpleName + "); open the app and reply manually"
            }
        }

        /** Re-evaluate app-level consent without changing Android's separate notification-listener grant. */
        fun refreshConsent() {
            val service = instance ?: return
            if (!OrionSensorConsent.read(service, OrionSensorScope.NOTIFICATIONS).active()) {
                synchronized(lock) { recent.clear() }
                return
            }
            try { service.activeNotifications?.forEach { service.capture(it) } } catch (_: Exception) { }
        }

        fun readSafeNotifications(): String {
            if (!OrionSensorConsent.read(instance ?: return "Notification access is not enabled. Open Android Notification access settings and enable Orion, then retry.", OrionSensorScope.NOTIFICATIONS).active()) {
                synchronized(lock) { recent.clear() }
                return "Orion notification context is disabled. Enable the Notification context grant in Settings → Sensor & context permissions."
            }
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
                    "$time · ${it.app}\nNotification key (for reply/dismiss): ${it.key}\n${it.title}\n${it.text}".trim()
                }.take(5000)
        }
    }
}
