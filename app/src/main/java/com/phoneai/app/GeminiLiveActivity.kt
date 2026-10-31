package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Optional short-lived, quota-visible Gemini Live conversation. Never exposes phone-control tools. */
internal class GeminiLiveActivity : Activity(), GeminiLiveSession.Listener, PhoneAiService.MainChatListener {
    private lateinit var visual: LiveCallVisualView
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var usageView: TextView
    private lateinit var micButton: TextView
    private lateinit var endButton: TextView
    private lateinit var standardModeButton: TextView

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: GeminiLiveSession? = null
    private var attachedService: PhoneAiService? = null
    @Volatile private var active = false
    @Volatile private var liveConnected = false
    @Volatile private var microphoneEnabled = true
    @Volatile private var quotaWarningAccepted = false
    private val transcripts = ArrayDeque<String>()
    @Volatile private var lastHandoffCommand = ""
    @Volatile private var pendingWakeTranscript = false

    private val sessionLimit = Runnable {
        if (active) {
            session?.stop()
            session = null
            active = false
            visual.mode = OrbView.Mode.IDLE
            statusView.text = "SESSION LIMIT REACHED"
            transcriptView.text = "The five-minute Gemini Live session limit has been reached. End this call or start another session after reviewing the quota warning."
            Toast.makeText(this, "Gemini Live session ended at the five-minute limit.", Toast.LENGTH_LONG).show()
            updateControls()
        }
    }

    private val connectTimeout = Runnable {
        if (active && !liveConnected) {
            session?.stop()
            session = null
            active = false
            visual.mode = OrbView.Mode.IDLE
            statusView.text = "CONNECTION TIMEOUT"
            transcriptView.text = "Gemini Live did not finish connecting. Check the network and Gemini API access before retrying."
            updateControls()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    private fun control(text: String, primary: Boolean = false, action: () -> Unit): TextView =
        label(text, 13f, if (primary) Color.BLACK else Theme.TEXT, true).apply {
            minHeight = dp(48)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (primary) Theme.SILVER else 0xFF171717.toInt())
                setStroke(dp(1), if (primary) Theme.SILVER else 0xFF3C3C3C.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(18), dp(22), dp(20))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(label("ORION  ·  GEMINI LIVE", 11f, Theme.SILVER, true),
            LinearLayout.LayoutParams(-1, dp(32)))
        statusView = label("READY · NOT STREAMING", 11f, 0xFFBDBDBD.toInt(), true)
        root.addView(statusView, LinearLayout.LayoutParams(-1, dp(28)))

        visual = LiveCallVisualView(this).apply {
            style = LiveCallVisualView.Style.fromPref(Prefs.liveCallStyle(this@GeminiLiveActivity))
            mode = OrbView.Mode.IDLE
            contentDescription = "Gemini Live voice visualization"
            onVisualTap = { toggleMicrophone() }
        }
        root.addView(visual, LinearLayout.LayoutParams(-1, 0, 1f))

        transcriptView = label(
            "Gemini Live is an optional network voice mode. No microphone audio is streamed until you accept the quota warning.",
            17f, Theme.TEXT, true
        ).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(transcriptView, LinearLayout.LayoutParams(-1, dp(130)))

        usageView = label("No live session started · quota not yet used by this screen", 11f, 0xFF9B9B9B.toInt())
        usageView.maxLines = 2
        usageView.setPadding(dp(4), dp(6), dp(4), dp(8))
        root.addView(usageView, LinearLayout.LayoutParams(-1, dp(48)))

        micButton = control("Start microphone", true) { toggleMicrophone() }
        endButton = control("End call") { endCall(returnToStandard = false) }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(micButton, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        controls.addView(endButton, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        root.addView(controls, LinearLayout.LayoutParams(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        standardModeButton = label("Switch to standard Orion voice", 12f, Theme.SILVER, true).apply {
            setPadding(dp(10), dp(12), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { endCall(returnToStandard = true) }
        }
        root.addView(standardModeButton, LinearLayout.LayoutParams(-1, dp(44)))

        root.addView(label("Say “Orion, …” to hand an explicit request to Orion’s guarded tools · maximum 5 minutes", 10f, 0xFF787878.toInt()),
            LinearLayout.LayoutParams(-1, dp(30)))
        setContentView(root)

        val key = Prefs.key(this, "gemini")
        if (key.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Gemini API key required")
                .setMessage("Add your own Gemini API key in Orion Settings before enabling Gemini Live. The key is stored by Orion's encrypted credential vault.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        ensureMicrophoneThenWarn()
    }

    private fun ensureMicrophoneThenWarn() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showQuotaWarning()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    @Deprecated("Use Activity Result APIs after app's minimum API level is raised.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showQuotaWarning()
        } else {
            statusView.text = "MICROPHONE PERMISSION REQUIRED"
            transcriptView.text = "Gemini Live needs microphone access to stream voice. Nothing was sent. Enable microphone permission to use this optional mode."
            AlertDialog.Builder(this)
                .setTitle("Microphone permission is off")
                .setMessage("No audio has been sent. Grant microphone permission to start Gemini Live.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun showQuotaWarning() {
        if (isFinishing || quotaWarningAccepted) return
        AlertDialog.Builder(this)
            .setTitle("Before starting Gemini Live")
            .setMessage(
                GeminiLivePolicy.QUOTA_WARNING +
                    "\n\nYour microphone audio and conversation will be sent to Google's Gemini Live service. " +
                    "The original API key is used only to request a single-use, short-lived session token. " +
                    "No Orion phone-control tools are available in this mode."
            )
            .setPositiveButton("Accept and start (5 min max)") { _, _ ->
                quotaWarningAccepted = true
                startSession()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startSession() {
        if (active || isFinishing) return
        // The user's confirmation applies to this session only. Any reconnect/retry must show
        // the quota/privacy warning again before another microphone stream is started.
        quotaWarningAccepted = false
        val key = Prefs.key(this, "gemini")
        if (key.isBlank()) {
            statusView.text = "GEMINI KEY NOT AVAILABLE"
            Toast.makeText(this, "Add a Gemini key in Settings.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        active = true
        liveConnected = false
        microphoneEnabled = true
        visual.mode = OrbView.Mode.THINKING
        statusView.text = "CONNECTING · QUOTA MAY APPLY"
        transcriptView.text = "Connecting securely with a short-lived session token…"
        updateControls()
        main.removeCallbacks(sessionLimit)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(sessionLimit, GeminiLivePolicy.SESSION_LIMIT_MS)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        session = GeminiLiveSession(this, key, this).also { it.start() }
    }

    private fun toggleMicrophone() {
        if (!active) {
            if (quotaWarningAccepted) startSession() else showQuotaWarning()
            return
        }
        microphoneEnabled = !microphoneEnabled
        session?.setMicrophoneEnabled(microphoneEnabled)
        visual.mode = if (microphoneEnabled) OrbView.Mode.LISTENING else OrbView.Mode.IDLE
        statusView.text = if (microphoneEnabled) "MICROPHONE ON · GEMINI LIVE" else "MICROPHONE MUTED"
        updateControls()
    }

    private fun endCall(returnToStandard: Boolean) {
        main.removeCallbacks(sessionLimit)
        main.removeCallbacks(connectTimeout)
        session?.stop()
        session = null
        active = false
        visual.mode = OrbView.Mode.IDLE
        liveConnected = false
        statusView.text = if (returnToStandard) "STANDARD ORION MODE" else "CALL ENDED"
        updateControls()
        if (returnToStandard) {
            Prefs.setGeminiLiveEnabled(this, false)
            Toast.makeText(this, "Standard Orion live-call mode is restored.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun updateControls() {
        if (!::micButton.isInitialized || !::endButton.isInitialized) return
        micButton.text = when {
            !active -> "Start microphone"
            microphoneEnabled -> "Mute microphone"
            else -> "Resume microphone"
        }
        endButton.text = "End call"
        endButton.alpha = if (active) 1f else .65f
    }

    override fun onStatus(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            statusView.text = message.uppercase().take(90)
            visual.mode = when {
                message.contains("connect", true) -> OrbView.Mode.THINKING
                message.contains("error", true) || message.contains("quota", true) || message.contains("failed", true) -> OrbView.Mode.IDLE
                message.contains("ready", true) || message.contains("microphone", true) -> if (microphoneEnabled) OrbView.Mode.LISTENING else OrbView.Mode.IDLE
                else -> visual.mode
            }
            if (message.contains("connected", true)) {
                liveConnected = true
                main.removeCallbacks(connectTimeout)
            }
        }
    }

    override fun onTranscript(text: String, fromModel: Boolean) {
        runOnUiThread {
            if (isFinishing || text.isBlank()) return@runOnUiThread
            if (!fromModel) {
                val trimmed = text.trim()
                if (trimmed.equals("Orion", ignoreCase = true) || trimmed.equals("Hey Orion", ignoreCase = true)) {
                    pendingWakeTranscript = true
                    transcripts.addLast("YOU: $trimmed")
                    while (transcripts.size > 6) transcripts.removeFirst()
                    transcriptView.text = transcripts.joinToString("\\n")
                    return@runOnUiThread
                }
                val command = SharedToolSurfacePolicy.commandAfterWake(text)
                    ?: trimmed.takeIf { pendingWakeTranscript && it.isNotBlank() }
                if (command != null) {
                    pendingWakeTranscript = false
                    if (command.equals(lastHandoffCommand, ignoreCase = true)) return@runOnUiThread
                    lastHandoffCommand = command
                    transcripts.addLast("YOU: Orion, $command")
                    while (transcripts.size > 6) transcripts.removeFirst()
                    transcriptView.text = transcripts.joinToString("\\n")
                    val service = PhoneAiService.instance
                    if (service == null) {
                        statusView.text = "ORION ACCESSIBILITY SERVICE UNAVAILABLE"
                        usageView.text = "Enable Orion's accessibility service, then retry the command."
                        return@runOnUiThread
                    }
                    statusView.text = "HANDING OFF TO ORION TOOLS"
                    usageView.text = "Gemini Live is ending so Orion can run the request through app-approved tool permissions."
                    endCall(returnToStandard = true)
                    service.executeSharedToolRequest(command, "Gemini Live")
                    return@runOnUiThread
                }
            }
            if (fromModel) visual.mode = OrbView.Mode.SPEAKING else if (microphoneEnabled) visual.mode = OrbView.Mode.LISTENING
            val prefix = if (fromModel) "ORION: " else "YOU: "
            transcripts.addLast(prefix + text.trim())
            while (transcripts.size > 6) transcripts.removeFirst()
            transcriptView.text = transcripts.joinToString("\\n")
            persistLiveTurn(fromModel, text)
        }
    }

    /** Persist final live transcripts as text in the shared SQLite conversation index. Raw audio is never stored. */
    private fun persistLiveTurn(fromModel: Boolean, text: String) {
        val clean = text.trim().take(4_000)
        if (clean.isBlank() || MemoryGovernancePolicy.sensitivity(clean) == "sensitive") return
        runCatching {
            if (!fromModel) {
                MemoryAutoIndexer.observe(this, clean)
                    ?.takeIf { it.reviewStatus == "pending" && it.sourceRole == "user_observation" }
                    ?.let { proposal ->
                        OrionConversationRepository.get(this).recordEvent(
                            this, null, "memory_proposal", "pending",
                            "Suggested memory from Gemini Live for owner review: category=${proposal.category}"
                        )
                    }
            }
            val transcript = ChatHistoryStore.load(this).toMutableList()
            val turn = (if (fromModel) "ai" else "me") to clean
            if (transcript.lastOrNull() != turn) {
                transcript += turn
                ChatHistoryStore.save(this, transcript)
            }
        }
    }

    /** Partial speech recognition is intentionally ignored for execution; only final transcripts may hand off. */
    override fun onInterimTranscript(text: String) { }

    override fun onInputLevel(level: Float) {
        runOnUiThread {
            if (::visual.isInitialized) {
                visual.micLevel = if (microphoneEnabled) level.coerceIn(0f, 1f) else 0f
                if (active && microphoneEnabled && visual.mode != OrbView.Mode.SPEAKING) visual.mode = OrbView.Mode.LISTENING
            }
        }
    }

    override fun onUsage(message: String) {
        runOnUiThread { if (!isFinishing) usageView.text = message.take(260) }
    }

    override fun onError(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            main.removeCallbacks(sessionLimit)
            main.removeCallbacks(connectTimeout)
            active = false
            liveConnected = false
            session = null
            visual.mode = OrbView.Mode.IDLE
            visual.errorState = true
            statusView.text = "GEMINI LIVE ERROR"
            transcriptView.text = message
            usageView.text = "Session ended. Verify provider access/quota before retrying."
            updateControls()
            AlertDialog.Builder(this)
                .setTitle("Gemini Live stopped")
                .setMessage(message)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
        }
    }

    override fun onBackPressed() {
        endCall(returnToStandard = false)
    }

    override fun onResume() {
        super.onResume()
        val service = PhoneAiService.instance
        if (service != null && attachedService !== service) {
            attachedService?.detachMainChat(this)
            attachedService = service
            // This marks the visible Live screen as microphone owner and stops the wake recognizer.
            service.attachMainChat(this)
        }
    }

    override fun onStop() {
        // Do not leave mic capture active when another activity covers Orion or the phone is locked.
        if (active) {
            main.removeCallbacks(sessionLimit)
            main.removeCallbacks(connectTimeout)
            session?.stop()
            session = null
            active = false
            liveConnected = false
        }
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onStop()
    }

    // Gemini Live owns the transcript and output; service callbacks are intentionally ignored.
    override fun onMessage(kind: String, text: String) { }
    override fun onStatus(text: String, mode: OrbView.Mode) { }

    override fun onDestroy() {
        main.removeCallbacks(sessionLimit)
        main.removeCallbacks(connectTimeout)
        session?.stop()
        session = null
        active = false
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MIC = 4317
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
