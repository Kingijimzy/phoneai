package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dedicated hands-free conversation surface. Uses the same service/transcript as main chat,
 * with a lightweight native waveform so it remains usable on entry-level Android devices.
 */
class LiveConversationActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var wave: VoiceWaveView
    private lateinit var core: OrbView
    private lateinit var stateLabel: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var micButton: TextView
    private lateinit var helperLabel: TextView
    private var attachedService: PhoneAiService? = null
    private var listeningRequested = false
    private var lastMode = OrbView.Mode.IDLE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun panel(fill: Int, radius: Int, stroke: Int = Theme.LINE) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        applyImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF02080A.toInt(), 0xFF071311.toInt(), 0xFF0A1715.toInt()))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val back = text("‹", 34f, Theme.TEXT, false).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to chat"
            setPadding(dp(8), 0, dp(14), 0)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(46)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("ORION", 16f, Theme.TEXT, true))
        brand.addView(text("LIVE CONVERSATION", 9f, Theme.CYAN, true).apply { letterSpacing = .14f })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("● LIVE", 10f, Theme.OK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = panel(0xFF08251E.toInt(), 15, 0xFF167C66.toInt())
        })
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        core = OrbView(this).apply {
            backing = true
            contentDescription = "Orion live animated reactor core"
            mode = OrbView.Mode.IDLE
        }
        hero.addView(core, LinearLayout.LayoutParams(dp(210), dp(210)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(1)
        })
        wave = VoiceWaveView(this).apply {
            contentDescription = "Animated audio waveform that responds to Orion's current state"
        }
        hero.addView(wave, LinearLayout.LayoutParams(-1, dp(66)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(7)
        })
        hero.addView(text("ORION  /  LIVE CORE", 10f, Theme.CYAN, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .16f
        })
        stateLabel = text("Ready when you are", 23f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        hero.addView(stateLabel)
        helperLabel = text("Tap the microphone and speak naturally.", 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
        }
        hero.addView(helperLabel)
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))

        val transcriptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            background = panel(0xB50A1916.toInt(), 20, 0xFF203D38.toInt())
        }
        transcriptCard.addView(text("RECENT CONVERSATION", 9f, Theme.MUTED, true).apply {
            letterSpacing = .12f
        })
        val transcriptScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcriptScroll.addView(transcript)
        transcriptCard.addView(transcriptScroll, LinearLayout.LayoutParams(-1, dp(92)).apply {
            topMargin = dp(5)
        })
        root.addView(transcriptCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val end = text("×", 24f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "End live conversation"
            background = panel(0xFF101B19.toInt(), 22, 0xFF2C504E.toInt())
            setOnClickListener { finish() }
        }
        controls.addView(end, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(18) })
        micButton = text("●", 24f, 0xFF02110D.toInt(), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Start voice input"
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB9FFF0.toInt(), 0xFF00FCB6.toInt(), 0xFF0BB0A1.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFF72FFE7.toInt())
            }
            elevation = dp(7).toFloat()
            setOnClickListener {
                if (lastMode == OrbView.Mode.LISTENING) {
                    PhoneAiService.instance?.stopVoiceInput()
                    listeningRequested = false
                    helperLabel.text = "Listening stopped. Tap the core button when you're ready."
                } else {
                    startVoiceInput()
                }
            }
        }
        controls.addView(micButton, LinearLayout.LayoutParams(dp(76), dp(76)))
        val textChat = text("☰", 21f, Theme.CYAN, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Return to text chat"
            background = panel(0xFF101B19.toInt(), 22, 0xFF2C504E.toInt())
            setOnClickListener {
                startActivity(Intent(this@LiveConversationActivity, MainChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
            }
        }
        controls.addView(textChat, LinearLayout.LayoutParams(dp(58), dp(58)).apply { leftMargin = dp(18) })
        root.addView(controls)
        root.addView(text("VOICE ACCESS USES ANDROID SPEECH SERVICES", 9f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .05f
            setPadding(dp(4), dp(9), dp(4), dp(1))
        })
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
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
            Toast.makeText(this, "Enable Orion in Accessibility settings first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 73)
            return
        }
        listeningRequested = true
        service.startLiveConversation()
        helperLabel.text = "Speak naturally. Orion will respond here."
        micButton.text = "Ⅱ"
        micButton.contentDescription = "Voice input requested"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 73 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else if (requestCode == 73) {
            Toast.makeText(this, "Microphone permission is needed for voice conversation.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val service = PhoneAiService.instance
        if (service != null && attachedService !== service) {
            attachedService?.detachMainChat(this)
            attachedService = service
            transcript.removeAllViews()
            service.attachMainChat(this)
        }
    }

    override fun onStop() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onStop()
    }

    override fun onDestroy() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    override fun onMessage(kind: String, message: String) {
        runOnUiThread {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = panel(if (kind == "me") 0xFF10372E.toInt() else 0xFF101C19.toInt(), 12,
                    if (kind == "me") 0xFF167C66.toInt() else 0xFF203D38.toInt())
            }
            row.addView(text(if (kind == "me") "YOU" else "ORION", 8f,
                if (kind == "me") Theme.CYAN else Theme.MUTED, true))
            row.addView(text(message, 12f, Theme.TEXT).apply { maxLines = 3 })
            transcript.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(5)
            })
            while (transcript.childCount > 4) transcript.removeViewAt(0)
        }
    }

    override fun onStatus(value: String, mode: OrbView.Mode) {
        runOnUiThread {
            lastMode = mode
            wave.mode = mode
            core.mode = mode
            stateLabel.text = when (mode) {
                OrbView.Mode.IDLE -> "Ready when you are"
                OrbView.Mode.LISTENING -> "I’m listening"
                OrbView.Mode.THINKING -> "Let me think"
                OrbView.Mode.SPEAKING -> "I’m speaking"
                OrbView.Mode.ACTING -> "Working on it"
                OrbView.Mode.WAITING_APPROVAL -> "Your approval needed"
            }
            helperLabel.text = when (mode) {
                OrbView.Mode.IDLE -> "Tap the microphone to continue the conversation."
                OrbView.Mode.LISTENING -> "Go ahead — I’m listening to you."
                OrbView.Mode.THINKING -> "Connecting your words to the right response."
                OrbView.Mode.SPEAKING -> "You can listen, then tap the mic when ready."
                OrbView.Mode.ACTING -> "Orion is carrying out the requested task."
                OrbView.Mode.WAITING_APPROVAL -> "Please review the action request before proceeding."
            }
            micButton.text = if (mode == OrbView.Mode.LISTENING) "Ⅱ" else "●"
            micButton.contentDescription = if (mode == OrbView.Mode.LISTENING) "Stop listening" else "Start voice input"
            micButton.alpha = if (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING) .72f else 1f
        }
    }
}
