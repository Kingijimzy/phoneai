package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated speech, wake-word and live-voice settings page. */
class VoiceSettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var micStatus: TextView
    private lateinit var wakeStatus: TextView
    private var wakeSwitch: Switch? = null
    private var neuralSwitch: Switch? = null
    private var bindingControls = false
    private var neuralInstallButton: Button? = null
    private var wakeInstallButton: Button? = null
    private var geminiVoiceButton: Button? = null
    private var geminiSensitivityButton: Button? = null
    private var liveStyleButton: Button? = null
    private var installCancellation: AtomicBoolean? = null
    private var activeProgress: ProgressDialog? = null
    private var previewVoice: Voice? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Theme.PANEL)
        cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
        setStroke(dp(1), Theme.LINE)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        minHeight = dp(48)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isFocusable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.985f).scaleY(0.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun card(title: String, body: LinearLayout.() -> Unit) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(15), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            body()
        }
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        section.alpha = 0f
        section.translationY = dp(6).toFloat()
        section.post {
            section.animate().alpha(1f).translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    private fun addSwitch(container: LinearLayout, title: String, checked: Boolean, changed: (Boolean) -> Unit): Switch {
        val control = Switch(this).apply {
            text = title
            textSize = 13f
            setTextColor(Theme.TEXT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
            trackTintList = android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> if (!bindingControls) changed(value) }
        }
        container.addView(control, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        return control
    }

    private fun addNote(container: LinearLayout, text: String) {
        container.addView(label(text, 11f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(20), dp(17), dp(28))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  VOICE", 10f, Theme.SILVER, true))
        root.addView(label("Voice & speech", 27f, Theme.TEXT, true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        root.addView(label(
            "Control speech input, spoken replies and hands-free behavior. Offline-only settings fail closed instead of silently sending audio to a network service.",
            13f, Theme.MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })

        card("SPEECH OUTPUT") {
            addSwitch(this, "Speak Orion's answers", Prefs.voice(this@VoiceSettingsActivity)) {
                Prefs.setVoice(this@VoiceSettingsActivity, it)
            }
            addSwitch(this, "Deeper voice", Prefs.deepVoice(this@VoiceSettingsActivity)) {
                Prefs.setDeepVoice(this@VoiceSettingsActivity, it)
            }
            addSwitch(this, "Offline-only voice output", Prefs.offlineVoiceOnly(this@VoiceSettingsActivity)) {
                Prefs.setOfflineVoiceOnly(this@VoiceSettingsActivity, it)
                Toast.makeText(
                    this@VoiceSettingsActivity,
                    if (it) "Orion will stay silent if no installed offline voice is available."
                    else "Android may use its configured network TTS service.",
                    Toast.LENGTH_LONG
                ).show()
            }
            neuralSwitch = addSwitch(this, "Use installed English neural voice (Piper)", Prefs.neuralVoice(this@VoiceSettingsActivity)) {
                if (it && !NeuralVoiceInstaller.isInstalled(this@VoiceSettingsActivity)) {
                    Prefs.setNeuralVoice(this@VoiceSettingsActivity, false)
                    Toast.makeText(this@VoiceSettingsActivity, "Install the neural voice first.", Toast.LENGTH_LONG).show()
                    refresh()
                } else {
                    Prefs.setNeuralVoice(this@VoiceSettingsActivity, it)
                    Toast.makeText(
                        this@VoiceSettingsActivity,
                        if (it) "English neural voice enabled; synthesis runs on-device."
                        else "Android TextToSpeech selected.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            addNote(this, "Piper currently provides one English voice. Other languages continue using Android TextToSpeech.")
            neuralInstallButton = button(
                if (NeuralVoiceInstaller.isInstalled(this@VoiceSettingsActivity)) "Repair / reinstall English neural voice"
                else "Install English neural voice · about 21 MB"
            ) { installNeuralVoice() }
            addView(neuralInstallButton, LinearLayout.LayoutParams(-1, -2))
            addView(button("Choose Android speech voice") { chooseAndroidVoice() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Test spoken voice") { testVoice() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addNote(this, "Speaking speed")
            addSlider(this, ((Prefs.rate(this@VoiceSettingsActivity) - 0.5f) * 100f).toInt().coerceIn(0, 100)) { progress ->
                Prefs.setRate(this@VoiceSettingsActivity, 0.5f + progress / 100f)
            }
        }

        card("SPEECH INPUT & HANDS-FREE") {
            micStatus = label("", 12f, Theme.MUTED).apply {
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(micStatus)
            addView(button("Open Android microphone permissions") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addSwitch(this, "Hands-free: keep listening after each answer", Prefs.hands(this@VoiceSettingsActivity)) {
                Prefs.setHands(this@VoiceSettingsActivity, it)
            }
            addSwitch(this, "Offline-only speech input (no network fallback)", Prefs.offlineSpeechOnly(this@VoiceSettingsActivity)) {
                Prefs.setOfflineSpeechOnly(this@VoiceSettingsActivity, it)
                Toast.makeText(
                    this@VoiceSettingsActivity,
                    if (it) "If Android has no offline recognition model, voice input stops instead of sending audio online."
                    else "Android may use its configured speech-recognition service.",
                    Toast.LENGTH_LONG
                ).show()
            }
            addSwitch(this, "Wake-word test mode", Prefs.wakeTestMode(this@VoiceSettingsActivity)) {
                Prefs.setWakeTestMode(this@VoiceSettingsActivity, it)
                if (Prefs.wake(this@VoiceSettingsActivity)) PhoneAiService.instance?.updateWakeWord(true)
            }
            addNote(this, "Offline-only speech applies to spoken turns. Wake detection is experimental and depends on Android microphone access.")
        }

        card("WAKE WORD") {
            wakeStatus = label("", 12f, Theme.MUTED).apply {
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(wakeStatus)
            wakeInstallButton = button(
                if (WakeWordModelInstaller.isInstalled(this@VoiceSettingsActivity)) "Offline wake-word model · installed"
                else "Install dedicated offline wake-word model · about 16 MB"
            ) { installWakeWordModel() }
            addView(wakeInstallButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addNote(this, "Sensitivity")
            addSlider(this, Prefs.wakeSensitivity(this@VoiceSettingsActivity)) { progress ->
                Prefs.setWakeSensitivity(this@VoiceSettingsActivity, progress)
                wakeStatus.text = wakeStatusText()
            }
            wakeSwitch = addSwitch(this, "Wake word: say Orion", Prefs.wake(this@VoiceSettingsActivity)) { enabled ->
                val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (enabled && !WakeWordModelInstaller.isInstalled(this@VoiceSettingsActivity)) {
                    Prefs.setWake(this@VoiceSettingsActivity, false)
                    Toast.makeText(this@VoiceSettingsActivity, "Install the offline wake-word model first.", Toast.LENGTH_LONG).show()
                    refresh()
                } else if (enabled && !hasMic) {
                    Prefs.setWake(this@VoiceSettingsActivity, false)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                    Toast.makeText(this@VoiceSettingsActivity, "Allow microphone access, then enable wake word again.", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setWake(this@VoiceSettingsActivity, enabled)
                    PhoneAiService.instance?.updateWakeWord(enabled)
                    Toast.makeText(
                        this@VoiceSettingsActivity,
                        if (enabled) "Wake word enabled. Say Orion followed by your command."
                        else "Wake word disabled.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refresh()
            }
            addNote(this, "Audio is processed on-device when the offline model is installed. Android speech recognition starts only after the wake phrase is detected.")
        }

        card("GEMINI LIVE") {
            addSwitch(this, "Enable experimental Gemini Live mode", Prefs.geminiLiveEnabled(this@VoiceSettingsActivity)) {
                Prefs.setGeminiLiveEnabled(this@VoiceSettingsActivity, it)
                Toast.makeText(
                    this@VoiceSettingsActivity,
                    if (it) "Gemini Live enabled for the next call. A quota warning appears before each session."
                    else "Standard Orion live call restored.",
                    Toast.LENGTH_LONG
                ).show()
            }
            geminiVoiceButton = button("Gemini Live voice · " + Prefs.geminiVoice(this@VoiceSettingsActivity)) {
                val voices = Prefs.GEMINI_VOICES.toTypedArray()
                AlertDialog.Builder(this@VoiceSettingsActivity)
                    .setTitle("Gemini Live voice")
                    .setSingleChoiceItems(voices, voices.indexOf(Prefs.geminiVoice(this@VoiceSettingsActivity)).coerceAtLeast(0)) { dialog, which ->
                        Prefs.setGeminiVoice(this@VoiceSettingsActivity, voices[which])
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addView(geminiVoiceButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            geminiSensitivityButton = button("Gemini Live mic sensitivity · " + Prefs.geminiMicSensitivity(this@VoiceSettingsActivity)) {
                val levels = arrayOf("low", "medium", "high")
                AlertDialog.Builder(this@VoiceSettingsActivity)
                    .setTitle("How readily Gemini detects that you are speaking")
                    .setSingleChoiceItems(levels, levels.indexOf(Prefs.geminiMicSensitivity(this@VoiceSettingsActivity)).coerceAtLeast(0)) { dialog, which ->
                        Prefs.setGeminiMicSensitivity(this@VoiceSettingsActivity, levels[which])
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addView(geminiSensitivityButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addNote(this, "Gemini Live streams microphone audio to Google and uses usage-based quota. Each session is capped at five minutes; account limits and costs vary.")
        }

        card("LIVE-CALL PRESENTATION") {
            liveStyleButton = button("Live-call visual · " + liveStyleLabel()) {
                val keys = arrayOf("orb", "dot_matrix", "eyes")
                val names = arrayOf("Glass orb", "Dot matrix", "Eyes")
                AlertDialog.Builder(this@VoiceSettingsActivity)
                    .setTitle("Live-call visual")
                    .setSingleChoiceItems(names, keys.indexOf(Prefs.liveCallStyle(this@VoiceSettingsActivity)).coerceAtLeast(0)) { dialog, which ->
                        Prefs.setLiveCallStyle(this@VoiceSettingsActivity, keys[which])
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addView(liveStyleButton, LinearLayout.LayoutParams(-1, -2))
        }

        root.addView(button("Back to settings") { finish() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(root)
        })
        refresh()
    }

    private fun addSlider(container: LinearLayout, initial: Int, changed: (Int) -> Unit) {
        val slider = SeekBar(this).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
            thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) changed(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        container.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
    }

    private fun refresh() {
        if (!::micStatus.isInitialized) return
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "Microphone permission: granted by Android." else "Microphone permission: not granted. Orion cannot listen until Android grants it."
        bindingControls = true
        wakeSwitch?.isChecked = Prefs.wake(this)
        neuralSwitch?.isChecked = Prefs.neuralVoice(this)
        bindingControls = false
        wakeStatus.text = wakeStatusText()
        neuralInstallButton?.text = if (NeuralVoiceInstaller.isInstalled(this)) "Repair / reinstall English neural voice" else "Install English neural voice · about 21 MB"
        wakeInstallButton?.text = if (WakeWordModelInstaller.isInstalled(this)) "Offline wake-word model · installed" else "Install dedicated offline wake-word model · about 16 MB"
        geminiVoiceButton?.text = "Gemini Live voice · " + Prefs.geminiVoice(this)
        geminiSensitivityButton?.text = "Gemini Live mic sensitivity · " + Prefs.geminiMicSensitivity(this)
        liveStyleButton?.text = "Live-call visual · " + liveStyleLabel()
    }

    private fun wakeStatusText(): String = buildString {
        append("Wake word: ").append(if (Prefs.wake(this@VoiceSettingsActivity)) "enabled" else "disabled")
        append(" · sensitivity ").append(Prefs.wakeSensitivity(this@VoiceSettingsActivity)).append("/100")
        append(" · threshold ").append(String.format(Locale.US, "%.2f", Prefs.wakeThreshold(this@VoiceSettingsActivity)))
        append(" · offline model ").append(if (WakeWordModelInstaller.isInstalled(this@VoiceSettingsActivity)) "installed" else "not installed")
    }

    private fun liveStyleLabel(): String = when (Prefs.liveCallStyle(this)) {
        "dot_matrix" -> "Dot matrix"
        "eyes" -> "Eyes"
        else -> "Glass orb"
    }

    private fun chooseAndroidVoice() {
        val voice = Voice(this)
        previewVoice = voice
        Toast.makeText(this, "Loading Android voices…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            if (isFinishing || isDestroyed) { voice.shutdown(); previewVoice = null; return@postDelayed }
            val list = voice.voiceList()
            if (list.isEmpty()) {
                Toast.makeText(this, "No voices found. Install Speech Services by Google.", Toast.LENGTH_LONG).show()
                voice.shutdown()
                previewVoice = null
                return@postDelayed
            }
            val labels = listOf("Automatic · Best available") + list.map {
                it.name + " · " + it.locale.displayName + if (it.isNetworkConnectionRequired) " · network" else " · offline"
            }
            val selected = list.indexOfFirst { it.name == Prefs.voiceName(this) } + 1
            AlertDialog.Builder(this)
                .setTitle("Choose Android speech voice")
                .setSingleChoiceItems(labels.toTypedArray(), selected.coerceAtLeast(0)) { dialog, which ->
                    Prefs.setVoiceName(this, if (which == 0) "" else list[which - 1].name)
                    voice.refreshVoice()
                    dialog.dismiss()
                    voice.speak("Hello. This is how I sound.") { runOnUiThread { voice.shutdown(); if (previewVoice === voice) previewVoice = null } }
                }
                .setNegativeButton("Cancel") { _, _ -> voice.shutdown(); if (previewVoice === voice) previewVoice = null }
                .setOnCancelListener { voice.shutdown(); if (previewVoice === voice) previewVoice = null }
                .show()
        }, 1_000L)
    }

    private fun testVoice() {
        val voice = Voice(this)
        previewVoice = voice
        voice.speak("Hello. This is Orion. This is a test of the current spoken voice.") {
            runOnUiThread { voice.shutdown(); if (previewVoice === voice) previewVoice = null }
        }
    }

    private fun installNeuralVoice() {
        if (installCancellation != null) {
            Toast.makeText(this, "Another voice download is still finishing.", Toast.LENGTH_SHORT).show()
            return
        }
        val cancelled = AtomicBoolean(false)
        installCancellation = cancelled
        val progress = ProgressDialog(this).apply {
            setTitle("Install English neural voice")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }
        activeProgress = progress
        NeuralVoiceInstaller.install(
            this,
            { cancelled.get() },
            { message, percent ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && progress.isShowing) {
                        progress.setMessage(message)
                        progress.progress = percent
                    }
                }
            },
            { failure ->
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (activeProgress === progress) activeProgress = null
                    if (installCancellation === cancelled) installCancellation = null
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failure == null) {
                        Prefs.setNeuralVoice(this, true)
                        Toast.makeText(this, "English neural voice installed and enabled.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Neural voice install failed: " + (failure.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        )
    }

    private fun installWakeWordModel() {
        if (installCancellation != null) {
            Toast.makeText(this, "Another voice download is still finishing.", Toast.LENGTH_SHORT).show()
            return
        }
        val cancelled = AtomicBoolean(false)
        installCancellation = cancelled
        val progress = ProgressDialog(this).apply {
            setTitle("Install offline wake-word model")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
            setOnCancelListener { cancelled.set(true) }
            show()
        }
        activeProgress = progress
        WakeWordModelInstaller.install(
            this,
            { cancelled.get() },
            { message, percent ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && progress.isShowing) {
                        progress.setMessage(message)
                        progress.progress = percent
                    }
                }
            },
            { failure ->
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (activeProgress === progress) activeProgress = null
                    if (installCancellation === cancelled) installCancellation = null
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failure == null) {
                        Toast.makeText(this, "Offline wake-word model installed. Enable the wake word when ready.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Wake-word model install failed: " + (failure.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        installCancellation?.set(true)
        activeProgress?.let { if (it.isShowing) it.dismiss() }
        activeProgress = null
        previewVoice?.shutdown()
        previewVoice = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            refresh()
            Toast.makeText(
                this,
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                    "Microphone permission granted. Enable the wake word again when ready."
                else "Microphone permission denied. Wake word remains disabled.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val REQUEST_MIC = 741
    }
}
