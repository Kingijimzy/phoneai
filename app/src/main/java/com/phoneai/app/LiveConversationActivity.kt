package com.phoneai.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
    private lateinit var liveDot: View
    private var statusPulse: AnimatorSet? = null
    private lateinit var micCaption: TextView
    private var attachedService: PhoneAiService? = null
    private var listeningRequested = false
    private var lastMode = OrbView.Mode.IDLE
    private var micPulse: AnimatorSet? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Subtle tactile response for the live-call controls; returns false so click handling remains intact. */
    private fun addPressFeedback(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touched.animate().scaleX(.94f).scaleY(.94f)
                    .setDuration(85L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touched.animate().scaleX(1f).scaleY(1f)
                    .setDuration(150L).start()
            }
            false
        }
    }
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
        intArrayOf(0xF21A2426.toInt(), fill, 0xF2080E10.toInt())
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (stroke == Theme.LINE) 0x554B6564 else stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        applyImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundColor(Theme.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(10))
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
        addPressFeedback(back)
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(46)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("ORION", 16f, Theme.TEXT, true))
        brand.addView(text("LIVE CONVERSATION", 9f, Theme.CYAN, true).apply { letterSpacing = .14f })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val liveBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(10), dp(7))
            background = panel(0xEE121A1D.toInt(), 11, Theme.LINE)
            liveDot = View(this@LiveConversationActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.OK) }
                contentDescription = "Voice session status"
            }
            addView(liveDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) })
            liveBadgeLabel = text("READY", 9f, Theme.MUTED, true)
            addView(liveBadgeLabel)
        }
        header.addView(liveBadge)
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(0), dp(10), dp(5))
        }
        val compact = resources.configuration.screenHeightDp < 720
        val waveHeight = if (compact) 46 else 58
        // Rebrand live voice around Orion's actual animated orb; waveform is supporting feedback.
        core = OrbView(this).apply {
            mode = OrbView.Mode.IDLE
            contentDescription = "Orion animated voice orb"
            isFocusable = false
        }
        hero.addView(core, LinearLayout.LayoutParams(dp(if (compact) 150 else 184), dp(if (compact) 150 else 184)).apply {
            bottomMargin = dp(if (compact) 12 else 18)
        })
        val voiceSurface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(if (compact) 11 else 15), dp(14), dp(if (compact) 11 else 15))
            background = panel(0xFF0B1113.toInt(), 16, 0xFF263A38.toInt())
        }
        val waveLabel = text("LIVE AUDIO", 9f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .16f
        }
        voiceSurface.addView(waveLabel)
        wave = VoiceWaveView(this).apply {
            contentDescription = "Animated audio waveform that responds to Orion's current state"
        }
        voiceSurface.addView(wave, LinearLayout.LayoutParams(-1, dp(waveHeight)).apply {
            topMargin = dp(8)
        })
        hero.addView(voiceSurface, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(if (compact) 13 else 18)
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
        helperLabel = text("Tap the mic to talk. Say “Orion, open…” before a phone command.", if (compact) 12f else 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        hero.addView(helperLabel)
        // Let content determine its height: weighted centering created the giant empty bands seen on-device.
        root.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(6) })

        val transcriptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = panel(0xE90C1214.toInt(), 15, 0xFF263633.toInt())
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
        transcriptCard.addView(transcriptScroll, LinearLayout.LayoutParams(-1, dp(if (compact) 72 else 88)).apply {
            topMargin = dp(6)
        })
        root.addView(transcriptCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val end = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "End live conversation"
            background = panel(0xFF11191B.toInt(), 17, 0xFF334443.toInt())
            isClickable = true; isFocusable = true
            addView(OrionIconView(this@LiveConversationActivity).apply {
                icon = "phone-off"; tint = Theme.MUTED; contentDescription = null
            }, LinearLayout.LayoutParams(dp(21), dp(21)))
            setOnClickListener {
                PhoneAiService.instance?.endLiveConversation()
                listeningRequested = false
                finish()
            }
        }
        addPressFeedback(end)
        val endItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(end, LinearLayout.LayoutParams(dp(46), dp(46)))
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
        micItem.addView(micButton, LinearLayout.LayoutParams(dp(if (compact) 58 else 64), dp(if (compact) 58 else 64)))
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
            background = panel(0xEE151E21.toInt(), 17, Theme.LINE)
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
        addPressFeedback(textChat)
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
        root.addView(text("VOICE SESSION  ·  TAP THE MIC TO PAUSE", 8f, 0xFF71817F.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .07f
            setPadding(dp(4), dp(7), dp(4), dp(1))
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
        micPulse?.cancel()
        micPulse = null
        statusPulse?.cancel()
        statusPulse = null
        micButton.scaleX = 1f
        micButton.scaleY = 1f
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
            row.addView(text(message, 12f, Theme.TEXT).apply {
                maxWidth = (resources.displayMetrics.widthPixels - dp(100)).coerceAtLeast(dp(180))
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(1).toFloat(), 1f)
            })
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
            val previousMode = lastMode
            lastMode = mode
            liveBadgeLabel.text = when (mode) {
                OrbView.Mode.IDLE -> "READY"
                OrbView.Mode.LISTENING -> "LISTENING"
                OrbView.Mode.THINKING -> "THINKING"
                OrbView.Mode.SPEAKING -> "SPEAKING"
                OrbView.Mode.ACTING -> "WORKING"
                OrbView.Mode.WAITING_APPROVAL -> "REVIEW"
            }
            liveBadgeLabel.setTextColor(when (mode) {
                OrbView.Mode.IDLE -> Theme.MUTED
                OrbView.Mode.WAITING_APPROVAL -> 0xFFE9B984.toInt()
                else -> Theme.OK
            })
            val statusColor = when (mode) {
                OrbView.Mode.IDLE -> 0xFF647775.toInt()
                OrbView.Mode.WAITING_APPROVAL -> 0xFFE9B984.toInt()
                OrbView.Mode.THINKING, OrbView.Mode.ACTING -> Theme.CYAN
                else -> Theme.OK
            }
            (liveDot.background as? GradientDrawable)?.setColor(statusColor)
            statusPulse?.cancel()
            statusPulse = null
            liveDot.animate().cancel()
            liveDot.scaleX = 1f
            liveDot.scaleY = 1f
            if (mode == OrbView.Mode.LISTENING || mode == OrbView.Mode.SPEAKING) {
                val pulseX = ObjectAnimator.ofFloat(liveDot, View.SCALE_X, 1f, 1.45f).apply {
                    duration = 720L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                }
                val pulseY = ObjectAnimator.ofFloat(liveDot, View.SCALE_Y, 1f, 1.45f).apply {
                    duration = 720L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                }
                statusPulse = AnimatorSet().apply { playTogether(pulseX, pulseY); start() }
            }
            micCaption.text = if (mode == OrbView.Mode.LISTENING) "STOP LISTENING" else "MICROPHONE"
            micCaption.setTextColor(if (mode == OrbView.Mode.LISTENING) Theme.OK else Theme.TEXT)
            wave.mode = mode
            core.mode = mode
            val nextState = when (mode) {
                OrbView.Mode.IDLE -> "Ready when you are"
                OrbView.Mode.LISTENING -> "I’m listening"
                OrbView.Mode.THINKING -> "Let me think"
                OrbView.Mode.SPEAKING -> "I’m speaking"
                OrbView.Mode.ACTING -> "Working on it"
                OrbView.Mode.WAITING_APPROVAL -> "Your approval needed"
            }
            if (previousMode != mode && stateLabel.text.toString() != nextState) {
                stateLabel.animate().cancel()
                stateLabel.animate().alpha(0f).translationY(dp(3).toFloat()).setDuration(90L)
                    .withEndAction {
                        stateLabel.text = nextState
                        stateLabel.animate().alpha(1f).translationY(0f).setDuration(170L).start()
                    }.start()
            } else {
                stateLabel.text = nextState
            }
            helperLabel.text = when (mode) {
                OrbView.Mode.IDLE -> "Tap the mic to talk. Say “Orion, open…” before a phone command."
                OrbView.Mode.LISTENING -> "Speak naturally. Say “Orion” first for phone actions."
                OrbView.Mode.THINKING -> "Connecting your words to the right response."
                OrbView.Mode.SPEAKING -> "I'll listen again after this reply. Tap the mic to interrupt."
                OrbView.Mode.ACTING -> "Orion is carrying out the requested task."
                OrbView.Mode.WAITING_APPROVAL -> "Please review the action request before proceeding."
            }
            micIcon.icon = if (mode == OrbView.Mode.LISTENING) "pause" else "mic"
            micButton.contentDescription = if (mode == OrbView.Mode.LISTENING) "Stop listening" else "Start voice input"
            if (mode == OrbView.Mode.LISTENING && micPulse == null) {
                val scaleX = ObjectAnimator.ofFloat(micButton, View.SCALE_X, 1f, 1.055f).apply {
                    duration = 780L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                }
                val scaleY = ObjectAnimator.ofFloat(micButton, View.SCALE_Y, 1f, 1.055f).apply {
                    duration = 780L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                }
                micPulse = AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                    startDelay = 80L
                    start()
                }
            } else if (mode != OrbView.Mode.LISTENING) {
                micPulse?.cancel()
                micPulse = null
                micButton.scaleX = 1f
                micButton.scaleY = 1f
            }
            val blocked = mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING ||
                mode == OrbView.Mode.WAITING_APPROVAL
            micButton.alpha = if (blocked) .72f else 1f
            micButton.isEnabled = !blocked
        }
    }
}
