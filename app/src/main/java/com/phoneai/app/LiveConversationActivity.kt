package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen live-call surface. One black canvas renderer serves both visual styles;
 * voice state and levels come from PhoneAiService/Voice, never a fabricated meter.
 */
class LiveConversationActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var visual: LiveCallVisualView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private lateinit var stepChip: TextView
    private lateinit var micButton: LinearLayout
    private lateinit var micIcon: OrionIconView
    private lateinit var micCaption: TextView
    private var attachedService: PhoneAiService? = null
    private var lastMode = OrbView.Mode.IDLE
    private var listeningRequested = false
    private var autoStarted = false
    private var redirectedToGemini = false
    private val main = Handler(Looper.getMainLooper())
    private val captionFade = Runnable { if (::caption.isInitialized) caption.animate().alpha(0f).setDuration(650L).start() }
    private val clearError = Runnable { if (::visual.isInitialized) visual.errorState = false }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun round(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.geminiLiveEnabled(this)) {
            // GeminiLiveActivity takes ownership of microphone/wake-word coordination.
            // Avoid restarting the wake listener as this routing Activity finishes.
            redirectedToGemini = true
            startActivity(Intent(this, GeminiLiveActivity::class.java))
            finish()
            return
        }
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        applyImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        status = label("ORION  ·  READY", 11f, 0xFF8CFFD0.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .16f
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        visual = LiveCallVisualView(this).apply {
            style = LiveCallVisualView.Style.fromPref(Prefs.liveCallStyle(this@LiveConversationActivity))
            onVisualTap = { handleVisualTap() }
        }
        root.addView(visual, LinearLayout.LayoutParams(-1, 0, 1f))

        stepChip = label("", 12f, 0xFF8CFFD0.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = round(0xFF07150F.toInt(), 0xFF285E4C.toInt(), 18)
            visibility = View.GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(stepChip, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(8)
        })

        caption = label("Ready when you are.", 25f, 0xFFF4FFF9.toInt(), true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
            alpha = .88f
            setPadding(dp(4), dp(8), dp(4), dp(9))
            contentDescription = "Live conversation captions"
        }
        root.addView(caption, LinearLayout.LayoutParams(-1, dp(100)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        val endItem = control("phone-off", "END", 0xFF202020.toInt(), 0xFF454545.toInt()) {
            PhoneAiService.instance?.endLiveConversation()
            listeningRequested = false
            finish()
        }
        controls.addView(endItem, LinearLayout.LayoutParams(0, -2, 1f))

        val micItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        micButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE8FFF4.toInt())
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            elevation = dp(5).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Start voice input"
            micIcon = OrionIconView(this@LiveConversationActivity).apply {
                icon = "mic"
                tint = 0xFF07150F.toInt()
                contentDescription = null
            }
            addView(micIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
            setOnClickListener { handleMicTap() }
        }
        micItem.addView(micButton, LinearLayout.LayoutParams(dp(66), dp(66)))
        micCaption = label("MIC", 10f, 0xFFF4FFF9.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .08f
            setPadding(0, dp(6), 0, 0)
        }
        micItem.addView(micCaption)
        controls.addView(micItem, LinearLayout.LayoutParams(0, -2, 1.15f))

        val chatItem = control("chat", "CHAT", 0xFF171717.toInt(), 0xFF454545.toInt()) {
            PhoneAiService.instance?.endLiveConversation()
            listeningRequested = false
            startActivity(Intent(this@LiveConversationActivity, MainChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
        }
        controls.addView(chatItem, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun control(iconName: String, captionText: String, fill: Int, stroke: Int, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = captionText
            val iconBox = LinearLayout(this@LiveConversationActivity).apply {
                gravity = Gravity.CENTER
                background = round(fill, stroke, 24)
                addView(OrionIconView(this@LiveConversationActivity).apply {
                    icon = iconName
                    tint = if (captionText == "END") 0xFFE4E4E4.toInt() else 0xFFF4FFF9.toInt()
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(23), dp(23)))
            }
            addView(iconBox, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(label(captionText, 10f, 0xFF9EAAA4.toInt(), true).apply {
                gravity = Gravity.CENTER
                letterSpacing = .07f
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { action() }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun startVoiceInput() {
        val service = PhoneAiService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Orion in Android Accessibility settings first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 73)
            return
        }
        listeningRequested = true
        service.startLiveConversation()
    }

    private fun handleMicTap() {
        when (lastMode) {
            OrbView.Mode.LISTENING -> {
                PhoneAiService.instance?.stopVoiceInput()
                listeningRequested = false
            }
            OrbView.Mode.SPEAKING -> startVoiceInput()
            OrbView.Mode.THINKING, OrbView.Mode.ACTING, OrbView.Mode.WAITING_APPROVAL ->
                Toast.makeText(this, "Orion is handling the current step.", Toast.LENGTH_SHORT).show()
            else -> startVoiceInput()
        }
    }

    private fun handleVisualTap() {
        when (lastMode) {
            OrbView.Mode.SPEAKING -> startVoiceInput()
            OrbView.Mode.LISTENING -> {
                PhoneAiService.instance?.stopVoiceInput()
                listeningRequested = false
            }
            OrbView.Mode.THINKING, OrbView.Mode.ACTING, OrbView.Mode.WAITING_APPROVAL -> {
                PhoneAiService.instance?.endLiveConversation()
                Toast.makeText(this, "Requested interruption. A running phone action may need to finish safely.", Toast.LENGTH_SHORT).show()
            }
            else -> startVoiceInput()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 73 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
        else if (requestCode == 73) Toast.makeText(this, "Microphone permission is required for live voice.", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        visual.animationEnabled = true
        visual.invalidate()
        val service = PhoneAiService.instance
        if (service != null && attachedService !== service) {
            attachedService?.detachMainChat(this)
            attachedService = service
            service.attachMainChat(this)
        }
        // Apply a Settings change immediately whenever this live screen resumes.
        visual.style = LiveCallVisualView.Style.fromPref(Prefs.liveCallStyle(this))
        if (!autoStarted && Prefs.hands(this)) {
            autoStarted = true
            visual.post { startVoiceInput() }
        }
    }

    override fun onPause() {
        if (::visual.isInitialized) visual.animationEnabled = false
        super.onPause()
    }

    override fun onStop() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(captionFade)
        main.removeCallbacks(clearError)
        if (isFinishing && !redirectedToGemini) PhoneAiService.instance?.endLiveConversation()
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    override fun onMessage(kind: String, message: String) {
        runOnUiThread {
            caption.animate().cancel()
            caption.text = message
            caption.alpha = 1f
            main.removeCallbacks(captionFade)
            main.postDelayed(captionFade, 7200L)
        }
    }

    override fun onAudioLevel(level: Float) {
        runOnUiThread { visual.micLevel = level.coerceIn(0f, 1f) }
    }

    override fun onAudioAmplitude(amplitude: Float) {
        runOnUiThread { visual.speechAmplitude = amplitude.coerceIn(0f, 1f) }
    }

    override fun onSpeechWord() {
        runOnUiThread { visual.pulseWord() }
    }

    override fun onStatus(value: String, mode: OrbView.Mode) {
        runOnUiThread {
            lastMode = mode
            visual.mode = mode
            visual.stepText = if (mode == OrbView.Mode.ACTING) value.take(54) else ""
            stepChip.visibility = if (mode == OrbView.Mode.ACTING && value.isNotBlank()) View.VISIBLE else View.GONE
            stepChip.text = value.take(54)
            status.text = "ORION  ·  " + when (mode) {
                OrbView.Mode.IDLE -> "READY"
                OrbView.Mode.LISTENING -> "LISTENING"
                OrbView.Mode.THINKING -> "THINKING"
                OrbView.Mode.SPEAKING -> "SPEAKING"
                OrbView.Mode.ACTING -> "WORKING"
                OrbView.Mode.WAITING_APPROVAL -> "APPROVAL"
            }
            status.setTextColor(if (mode == OrbView.Mode.WAITING_APPROVAL) 0xFFFFD49B.toInt() else 0xFF8CFFD0.toInt())
            micIcon.icon = if (mode == OrbView.Mode.LISTENING) "pause" else if (mode == OrbView.Mode.SPEAKING) "mic" else "mic"
            micCaption.text = when (mode) {
                OrbView.Mode.LISTENING -> "PAUSE"
                OrbView.Mode.SPEAKING -> "INTERRUPT"
                else -> "MIC"
            }
            micButton.contentDescription = when (mode) {
                OrbView.Mode.LISTENING -> "Stop listening"
                OrbView.Mode.SPEAKING -> "Interrupt Orion and speak"
                else -> "Start voice input"
            }
            val failed = value.contains("error", true) || value.contains("failed", true) ||
                value.contains("could not", true) || value.contains("unavailable", true)
            if (failed) {
                visual.errorState = true
                main.removeCallbacks(clearError)
                main.postDelayed(clearError, 850L)
                caption.text = value
                caption.alpha = 1f
            }
            if (mode == OrbView.Mode.WAITING_APPROVAL) caption.text = value
        }
    }

    override fun onTranscriptRestored(messages: List<Pair<String, String>>) {
        // The live call intentionally shows one uncluttered, fading caption rather than a chat card list.
        val latest = messages.lastOrNull() ?: return
        onMessage(latest.first, latest.second)
    }
}
