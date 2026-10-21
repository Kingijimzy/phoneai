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
    private lateinit var micButton: LinearLayout
    private lateinit var micIcon: OrionIconView
    private lateinit var helperLabel: TextView
    private lateinit var liveBadgeLabel: TextView
    private lateinit var micCaption: TextView
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
    /** Shared liquid-glass surface: a quiet top reflection over deep charcoal, never a neon glow. */
    private fun panel(fill: Int, radius: Int, stroke: Int = Theme.LINE) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(fill, 0xE80D1416.toInt())
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (stroke == Theme.LINE) 0x663D6963 else stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        applyImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(Theme.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val back = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to chat"
            isClickable = true
            isFocusable = true
            addView(OrionIconView(this@LiveConversationActivity).apply {
                icon = "arrow"; tint = Theme.TEXT; rotation = 180f; contentDescription = null
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(46)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("ORION", 16f, Theme.TEXT, true))
        brand.addView(text("LIVE CONVERSATION", 9f, Theme.CYAN, true).apply { letterSpacing = .14f })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val liveBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(10), dp(7))
            background = panel(0xEE121A1D.toInt(), 15, Theme.LINE)
            addView(View(this@LiveConversationActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.OK) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) })
            liveBadgeLabel = text("READY", 9f, Theme.MUTED, true)
            addView(liveBadgeLabel)
        }
        header.addView(liveBadge)
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        val compact = resources.configuration.screenHeightDp < 720
        val coreSize = if (compact) 132 else 172
        val waveHeight = if (compact) 30 else 42
        core = OrbView(this).apply {
            backing = true
            contentDescription = "Orion live animated reactor core"
            mode = OrbView.Mode.IDLE
        }
        hero.addView(core, LinearLayout.LayoutParams(dp(coreSize), dp(coreSize)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(1)
        })
        wave = VoiceWaveView(this).apply {
            contentDescription = "Animated audio waveform that responds to Orion's current state"
        }
        hero.addView(wave, LinearLayout.LayoutParams(dp(236), dp(waveHeight)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(3)
        })
        hero.addView(text("VOICE SESSION", 9f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .18f
        })
        stateLabel = text("Ready when you are", if (compact) 21f else 24f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = -.025f
            setPadding(0, dp(if (compact) 4 else 8), 0, dp(4))
        }
        hero.addView(stateLabel)
        helperLabel = text("Tap the microphone and speak naturally.", if (compact) 12f else 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        hero.addView(helperLabel)
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))

        val transcriptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(9))
            background = panel(0xE811191C.toInt(), 18, Theme.LINE)
        }
        val transcriptHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transcriptHeading.addView(text("CONVERSATION", 9f, Theme.MUTED, true).apply {
            letterSpacing = .12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        transcriptHeading.addView(text("LATEST MESSAGES", 8f, 0xFF71817F.toInt(), true).apply {
            letterSpacing = .06f
        })
        transcriptCard.addView(transcriptHeading)
        val transcriptScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcriptScroll.addView(transcript)
        transcriptCard.addView(transcriptScroll, LinearLayout.LayoutParams(-1, dp(if (compact) 64 else 92)).apply {
            topMargin = dp(5)
        })
        root.addView(transcriptCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val end = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "End live conversation"
            background = panel(0xEE151E21.toInt(), 22, Theme.LINE)
            isClickable = true; isFocusable = true
            addView(OrionIconView(this@LiveConversationActivity).apply {
                icon = "phone-off"; tint = Theme.MUTED; contentDescription = null
            }, LinearLayout.LayoutParams(dp(23), dp(23)))
            setOnClickListener {
                PhoneAiService.instance?.endLiveConversation()
                listeningRequested = false
                finish()
            }
        }
        val endItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(end, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(text("END CALL", 8f, Theme.MUTED, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = .04f
                setPadding(0, dp(5), 0, 0)
            })
        }
        controls.addView(endItem, LinearLayout.LayoutParams(dp(82), -2))
        val micItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        micButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Start voice input"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.CYAN)
                setStroke(dp(1), 0xFFB4EEE5.toInt())
            }
            elevation = dp(7).toFloat()
            isClickable = true; isFocusable = true
            micIcon = OrionIconView(this@LiveConversationActivity).apply {
                icon = "mic"; tint = Theme.ON_ACCENT; contentDescription = null
            }
            addView(micIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
            setOnClickListener {
                if (lastMode == OrbView.Mode.LISTENING) {
                    PhoneAiService.instance?.stopVoiceInput()
                    listeningRequested = false
                    helperLabel.text = "Listening stopped. Tap the microphone when you're ready."
                } else if (lastMode == OrbView.Mode.THINKING || lastMode == OrbView.Mode.ACTING ||
                    lastMode == OrbView.Mode.WAITING_APPROVAL) {
                    Toast.makeText(this@LiveConversationActivity, "Orion is busy. Wait for the current step to finish.", Toast.LENGTH_SHORT).show()
                } else {
                    startVoiceInput()
                }
            }
        }
        micItem.addView(micButton, LinearLayout.LayoutParams(dp(if (compact) 62 else 68), dp(if (compact) 62 else 68)))
        micCaption = text("MICROPHONE", 8f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .04f
            setPadding(0, dp(5), 0, 0)
        }
        micItem.addView(micCaption)
        controls.addView(micItem, LinearLayout.LayoutParams(dp(94), -2))
        val textChat = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Return to text chat"
            background = panel(0xEE151E21.toInt(), 22, Theme.LINE)
            isClickable = true; isFocusable = true
            addView(OrionIconView(this@LiveConversationActivity).apply {
                icon = "chat"; tint = Theme.CYAN; contentDescription = null
            }, LinearLayout.LayoutParams(dp(23), dp(23)))
            setOnClickListener {
                PhoneAiService.instance?.endLiveConversation()
                listeningRequested = false
                startActivity(Intent(this@LiveConversationActivity, MainChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
            }
        }
        val textItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(textChat, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(text("TEXT CHAT", 8f, Theme.MUTED, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = .04f
                setPadding(0, dp(5), 0, 0)
            })
        }
        controls.addView(textItem, LinearLayout.LayoutParams(dp(82), -2))
        root.addView(controls)
        root.addView(text("VOICE IS PROCESSED THROUGH YOUR SELECTED ANDROID SPEECH SERVICE", 8f, 0xFF71817F.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .025f
            setPadding(dp(4), dp(10), dp(4), dp(1))
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
        micIcon.icon = "pause"
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
        if (isFinishing) PhoneAiService.instance?.endLiveConversation()
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    override fun onMessage(kind: String, message: String) {
        runOnUiThread {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = panel(if (kind == "me") 0xFF203A34.toInt() else 0xFF11191C.toInt(), 12,
                    if (kind == "me") 0xFF426F67.toInt() else Theme.LINE)
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

    override fun onAudioLevel(level: Float) {
        runOnUiThread { wave.audioLevel = level }
    }

    override fun onStatus(value: String, mode: OrbView.Mode) {
        runOnUiThread {
            lastMode = mode
            liveBadgeLabel.text = when (mode) {
                OrbView.Mode.IDLE -> "READY"
                OrbView.Mode.LISTENING -> "LISTENING"
                OrbView.Mode.THINKING -> "THINKING"
                OrbView.Mode.SPEAKING -> "SPEAKING"
                OrbView.Mode.ACTING -> "WORKING"
                OrbView.Mode.WAITING_APPROVAL -> "REVIEW"
            }
            liveBadgeLabel.setTextColor(if (mode == OrbView.Mode.IDLE) Theme.MUTED else Theme.OK)
            micCaption.text = if (mode == OrbView.Mode.LISTENING) "STOP LISTENING" else "MICROPHONE"
            micCaption.setTextColor(if (mode == OrbView.Mode.LISTENING) Theme.OK else Theme.TEXT)
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
            micIcon.icon = if (mode == OrbView.Mode.LISTENING) "pause" else "mic"
            micButton.contentDescription = if (mode == OrbView.Mode.LISTENING) "Stop listening" else "Start voice input"
            val blocked = mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING ||
                mode == OrbView.Mode.WAITING_APPROVAL
            micButton.alpha = if (blocked) .72f else 1f
            micButton.isEnabled = !blocked
        }
    }
}
