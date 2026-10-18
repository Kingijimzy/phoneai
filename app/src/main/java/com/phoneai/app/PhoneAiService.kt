package com.phoneai.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** A screenshot shrunk for the vision model. scale = real width / sent width. */
class Shot(val b64: String, val scale: Float, val w: Int, val h: Int)

class PhoneAiService : AccessibilityService(), AgentUi {

    companion object {
        @Volatile
        var instance: PhoneAiService? = null

        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val OVERLAY = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        private const val NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        private const val NOT_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        private val WAKE = Regex("\\b(orion|orian|oryan|orrion|oreon|o ryan|o'ryan)\\b", RegexOption.IGNORE_CASE)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val shotExec = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var agent: Agent
    private lateinit var voice: Voice

    private val transcript = CopyOnWriteArrayList<Pair<String, String>>()
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private var bubble: OrbView? = null
    private var panel: LinearLayout? = null
    private var panelOrb: OrbView? = null
    private var panelStatus: TextView? = null
    private var micBtnRef: TextView? = null
    private var strip: LinearLayout? = null
    private var stripOrb: OrbView? = null
    private var stripText: TextView? = null
    private var confirmView: LinearLayout? = null
    private var logBox: LinearLayout? = null
    private var logScroll: ScrollView? = null
    private var pendingConfirm: ((Boolean) -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    private var speaking = false

    @Volatile
    private var phase = "thinking"
    private var listening = false
    private var handsActive = false
    private var emptyListens = 0
    private var wakeGen = 0
    private var wakeErr = false

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        voice = Voice(this)
        voice.onSpeaking = { s ->
            speaking = s
            refreshMode()
        }
        voice.onLevel = { l ->
            for (o in orbs()) o.level = l
        }
        agent = Agent(this, this)
        showBubble()
        startWake()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        wakeGen++
        removeAllViews()
        try {
            voice.shutdown()
        } catch (e: Exception) {
        }
        return super.onUnbind(intent)
    }

    private fun removeAllViews() {
        for (v in listOf<View?>(bubble, panel, strip, confirmView)) {
            if (v != null) {
                try {
                    wm.removeView(v)
                } catch (e: Exception) {
                }
            }
        }
        bubble = null
        panel = null
        panelOrb = null
        panelStatus = null
        strip = null
        stripOrb = null
        stripText = null
        confirmView = null
    }

    // ---------------------------------------------------------------- small UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val b = wm.maximumWindowMetrics.bounds
        return Pair(b.width(), b.height())
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radius
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(fg)
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        t.background = rounded(bg, dp(14).toFloat())
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Theme.CYAN)
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(7), dp(12), dp(7))
        t.background = rounded(Theme.PANEL2, dp(16).toFloat(), Theme.LINE)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var longFired = false
        val longRun = Runnable {
            longFired = true
            onLong?.invoke()
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                    longFired = false
                    if (onLong != null) main.postDelayed(longRun, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        main.removeCallbacks(longRun)
                    }
                    lp.x = ox + dx
                    lp.y = oy + dy
                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (ex: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRun)
                    if (!moved && !longFired) onClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    true
                }
                else -> false
            }
        }
    }

    private fun onMainWait(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val l = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                l.countDown()
            }
        }
        l.await(2, TimeUnit.SECONDS)
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = vis
        panel?.visibility = vis
        strip?.visibility = vis
        confirmView?.visibility = vis
    }

    // ---------------------------------------------------------------- orb state

    private fun orbs(): List<OrbView> = listOfNotNull(bubble, panelOrb, stripOrb)

    /** Decides what the avatar should be doing and what the status line says. Main thread. */
    fun refreshAvatar() = refreshMode()

    private fun refreshMode() {
        main.post {
            val m = when {
                speaking -> OrbView.Mode.SPEAKING
                listening -> OrbView.Mode.LISTENING
                running -> if (phase == "acting") OrbView.Mode.ACTING else OrbView.Mode.THINKING
                else -> OrbView.Mode.IDLE
            }
            val selectedStyle = runCatching { OrbView.Style.valueOf(Prefs.avatarStyle(this)) }
                .getOrDefault(OrbView.Style.ORION_ORB)
            for (o in orbs()) {
                o.style = selectedStyle
                o.mode = m
            }
            micBtnRef?.text = if (listening) "Stop" else "Mic"
            panelStatus?.text = when (m) {
                OrbView.Mode.IDLE -> if (Prefs.wake(this)) "Say \"Orion\" or tap the mic" else "Ready"
                OrbView.Mode.LISTENING -> "Listening..."
                OrbView.Mode.THINKING -> "Thinking..."
                OrbView.Mode.SPEAKING -> "Speaking..."
                OrbView.Mode.ACTING -> "Working on it..."
            }
        }
    }

    // ---------------------------------------------------------------- floating button

    private fun showBubble() {
        if (bubble != null || running || panel != null) return
        val b = OrbView(this).apply {
            style = runCatching { OrbView.Style.valueOf(Prefs.avatarStyle(this@PhoneAiService)) }
                .getOrDefault(OrbView.Style.ORION_ORB)
        }
        b.backing = true
        val lp = WindowManager.LayoutParams(dp(64), dp(64), OVERLAY, NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = dp(220)
        makeDraggable(b, lp, { openPanel() }, { quickListen() })
        wm.addView(b, lp)
        bubble = b
        refreshMode()
    }

    private fun hideBubble() {
        val b = bubble
        if (b != null) {
            try {
                wm.removeView(b)
            } catch (e: Exception) {
            }
        }
        bubble = null
    }

    private fun quickListen() {
        handsActive = Prefs.hands(this)
        emptyListens = 0
        listenNow()
    }

    // ---------------------------------------------------------------- chat panel

    /** Called by the setup screen. */
    fun showPanel() {
        main.post { openPanel() }
    }

    private fun addLine(kind: String, text: String) {
        val box = logBox ?: return
        val t = TextView(this)
        t.text = text
        t.textSize = if (kind == "note") 11f else 15f
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(4), 0, dp(4))
        when (kind) {
            "me" -> {
                t.setTextColor(Theme.ON_ACCENT)
                t.background = rounded(Theme.CYAN, dp(16).toFloat())
                lp.gravity = Gravity.END
            }
            "ai" -> {
                t.setTextColor(Theme.TEXT)
                t.background = rounded(Theme.PANEL2, dp(16).toFloat(), Theme.LINE)
                lp.gravity = Gravity.START
            }
            else -> {
                t.setTextColor(Theme.MUTED)
                t.typeface = Typeface.MONOSPACE
            }
        }
        if (kind != "note") t.setPadding(dp(12), dp(8), dp(12), dp(8))
        box.addView(t, lp)
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openPanel() {
        if (panel != null || running) return
        wakeGen++
        hideBubble()
        hideStrip()
        val size = screenSize()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(Theme.PANEL, dp(24).toFloat(), Theme.LINE)
        root.setPadding(dp(14), dp(12), dp(14), dp(12))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val orb = OrbView(this).apply {
            style = runCatching { OrbView.Style.valueOf(Prefs.avatarStyle(this@PhoneAiService)) }
                .getOrDefault(OrbView.Style.ORION_ORB)
        }
        head.addView(orb, LinearLayout.LayoutParams(dp(76), dp(76)))
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.setPadding(dp(8), 0, 0, 0)
        val title = label("O R I O N", 16f, Theme.TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        titleCol.addView(title)
        val st = label("Ready", 13f, Theme.CYAN)
        titleCol.addView(st)
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val fresh = label("New", 14f, Theme.MUTED)
        fresh.setPadding(dp(10), dp(6), dp(10), dp(6))
        fresh.setOnClickListener { newChat() }
        head.addView(fresh)
        val close = label("Close", 14f, Theme.MUTED)
        close.setPadding(dp(10), dp(6), dp(10), dp(6))
        close.setOnClickListener { closePanel(true) }
        head.addView(close)
        root.addView(head)
        panelOrb = orb
        panelStatus = st

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        logBox = box
        logScroll = scroll
        if (transcript.isEmpty()) {
            val n = Prefs.userName(this)
            addLine("ai", "Hello" + (if (n.isEmpty()) "" else ", $n") + ". I'm Orion. Type, tap the mic, or hold the orb and speak. Tell me what to do.")
        } else {
            for (item in transcript) addLine(item.first, item.second)
        }

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        val quick = listOf(
            "What's on screen" to "Look at my screen and tell me what is on it.",
            "Battery" to "What is my battery level?",
            "Record screen" to "Start recording my screen.",
            "Stop recording" to "Stop the screen recording.",
            "Flashlight" to "Toggle the flashlight on.",
            "FC Mobile" to "Open FC Mobile."
        )
        for ((chipName, cmd) in quick) {
            val c = chip(chipName) { submit(cmd) }
            val clp = LinearLayout.LayoutParams(WRAP, WRAP)
            clp.setMargins(0, 0, dp(6), 0)
            chips.addView(c, clp)
        }
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips)
        val chipLp = LinearLayout.LayoutParams(MATCH, WRAP)
        chipLp.setMargins(0, dp(6), 0, dp(6))
        root.addView(chipScroll, chipLp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this)
        input.hint = "Message Orion..."
        input.setHintTextColor(Theme.MUTED)
        input.setTextColor(Theme.TEXT)
        input.background = rounded(Theme.PANEL2, dp(18).toFloat(), Theme.LINE)
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ ->
            val t = input.text.toString()
            input.setText("")
            submit(t)
            true
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        lateinit var micBtn: TextView
        micBtn = pill("Mic", Theme.PANEL2, Theme.CYAN) {
            if (listening) {
                voice.stopListening()
                listening = false
                micBtn.text = "Mic"
                refreshMode()
            } else {
                handsActive = Prefs.hands(this)
                emptyListens = 0
                micBtn.text = "Stop"
                listenNow()
            }
        }
        val micLp = LinearLayout.LayoutParams(WRAP, WRAP)
        micLp.setMargins(dp(8), 0, 0, 0)
        row.addView(micBtn, micLp)
        micBtnRef = micBtn
        val send = pill("Send", Theme.CYAN, Theme.ON_ACCENT) {
            val t = input.text.toString()
            input.setText("")
            submit(t)
        }
        val sendLp = LinearLayout.LayoutParams(WRAP, WRAP)
        sendLp.setMargins(dp(8), 0, 0, 0)
        row.addView(send, sendLp)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            size.first - dp(24), (size.second * 0.62f).toInt(), OVERLAY, NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(12)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.addView(root, lp)
        panel = root
        refreshMode()
    }

    private fun closePanel(thenBubble: Boolean) {
        val p = panel
        if (p != null) {
            try {
                wm.removeView(p)
            } catch (e: Exception) {
            }
        }
        panel = null
        panelOrb = null
        panelStatus = null
        micBtnRef = null
        logBox = null
        logScroll = null
        if (thenBubble) {
            if (listening) {
                voice.stopListening()
                listening = false
            }
            showBubble()
            startWake()
        }
    }

    private fun newChat() {
        agent.reset()
        transcript.clear()
        logBox?.removeAllViews()
        addLine("ai", "New chat. What should I do?")
    }

    // ---------------------------------------------------------------- status strip and confirm

    private fun showStrip(text: String) {
        val existing = stripText
        if (strip != null && existing != null) {
            existing.text = text
            return
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rounded(0xF00E1522.toInt(), dp(22).toFloat(), Theme.CYAN)
        row.setPadding(dp(8), dp(6), dp(10), dp(6))
        val orb = OrbView(this).apply {
            style = runCatching { OrbView.Style.valueOf(Prefs.avatarStyle(this@PhoneAiService)) }
                .getOrDefault(OrbView.Style.ORION_ORB)
        }
        row.addView(orb, LinearLayout.LayoutParams(dp(48), dp(48)))
        val t = label(text, 14f, Theme.TEXT)
        t.maxLines = 3
        t.setPadding(dp(8), 0, dp(8), 0)
        t.setOnClickListener {
            if (!running && !listening) {
                hideStrip()
                hideBubble()
                openPanel()
            }
        }
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val stop = pill("Stop", Theme.DANGER, 0xFFFFFFFF.toInt()) { stopAll() }
        row.addView(stop)
        val size = screenSize()
        val lp = WindowManager.LayoutParams(
            size.first - dp(24), WRAP, OVERLAY, NOT_FOCUSABLE or NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(8)
        wm.addView(row, lp)
        strip = row
        stripOrb = orb
        stripText = t
        refreshMode()
    }

    private fun hideStrip() {
        val s = strip
        if (s != null) {
            try {
                wm.removeView(s)
            } catch (e: Exception) {
            }
        }
        strip = null
        stripOrb = null
        stripText = null
    }

    /** Shows text in the panel's status line if the panel is open, otherwise in the top strip. */
    private fun showStatusText(text: String) {
        if (panel != null) {
            panelStatus?.text = text
        } else {
            showStrip(text)
        }
    }

    private fun removeConfirm() {
        val c = confirmView
        if (c != null) {
            try {
                wm.removeView(c)
            } catch (e: Exception) {
            }
        }
        confirmView = null
    }

    private fun showConfirm(question: String, cb: (Boolean) -> Unit) {
        removeConfirm()
        val answer: (Boolean) -> Unit = { yes ->
            removeConfirm()
            pendingConfirm = null
            cb(yes)
        }
        pendingConfirm = answer
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(0xFF191409.toInt(), dp(20).toFloat(), Theme.WARN)
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.addView(label(question, 16f, Theme.TEXT))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val no = pill("No", Theme.PANEL2, Theme.TEXT) { answer(false) }
        val yes = pill("Yes, do it", Theme.CYAN, Theme.ON_ACCENT) { answer(true) }
        val noLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        noLp.setMargins(0, dp(12), dp(6), 0)
        val yesLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        yesLp.setMargins(dp(6), dp(12), 0, 0)
        row.addView(no, noLp)
        row.addView(yes, yesLp)
        box.addView(row)
        val size = screenSize()
        val lp = WindowManager.LayoutParams(
            size.first - dp(24), WRAP, OVERLAY, NOT_FOCUSABLE or NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        wm.addView(box, lp)
        confirmView = box
    }

    // ---------------------------------------------------------------- running commands

    private fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty() || running) return
        if (!Prefs.hasAnyKey(this)) {
            Toast.makeText(this, "Open the Orion AI app and add an API key first.", Toast.LENGTH_LONG).show()
            return
        }
        wakeGen++
        transcript.add(Pair("me", t))
        voice.stopSpeaking()
        voice.stopListening()
        listening = false
        closePanel(false)
        hideBubble()
        running = true
        phase = "thinking"
        showStrip("Thinking...")
        refreshMode()
        worker.execute {
            try {
                agent.run(t)
            } catch (e: Exception) {
                say("Error: ${e.message}")
            }
            main.post { finishRun() }
        }
    }

    private fun finishRun() {
        running = false
        refreshMode()
        val last = transcript.lastOrNull { it.first == "ai" }?.second ?: "Done."
        showStrip(last)
        if (handsActive && Prefs.hands(this)) {
            voice.whenIdle { listenNow() }
        } else {
            handsActive = false
            showBubble()
            startWake()
            main.postDelayed({
                if (!running && !listening) hideStrip()
            }, 7000)
        }
    }

    private fun listenNow() {
        if (running) return
        wakeGen++
        listening = true
        hideBubble()
        if (panel == null) showStrip("Listening...")
        voice.stopSpeaking()
        refreshMode()
        var errored = false
        voice.listen({ text ->
            listening = false
            refreshMode()
            if (text.isNullOrBlank()) {
                emptyListens++
                if (handsActive && emptyListens < 3 && !errored) {
                    main.postDelayed({ listenNow() }, 500)
                } else {
                    endHands()
                }
            } else {
                emptyListens = 0
                submit(text)
            }
        }, { partial ->
            showStatusText(partial)
        }, { err ->
            errored = true
            handsActive = false
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            transcript.add(Pair("note", err))
        })
    }

    private fun endHands() {
        handsActive = false
        listening = false
        if (panel == null) {
            hideStrip()
            showBubble()
            startWake()
        } else {
            refreshMode()
        }
    }

    private fun stopAll() {
        handsActive = false
        agent.stop()
        voice.stopSpeaking()
        voice.stopListening()
        listening = false
        pendingConfirm?.invoke(false)
        if (running) {
            stripText?.text = "Stopping..."
        } else {
            hideStrip()
            showBubble()
            startWake()
        }
        refreshMode()
    }

    // ---------------------------------------------------------------- wake word "Orion"

    private fun wakeAllowed(): Boolean =
        Prefs.wake(this) && !running && !listening && panel == null && confirmView == null &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWake() {
        if (!Prefs.wake(this)) return
        val my = ++wakeGen
        main.postDelayed({ wakeTick(my) }, 700)
    }

    private fun wakeTick(my: Int) {
        if (my != wakeGen || !wakeAllowed()) return
        if (voice.isSpeaking()) {
            main.postDelayed({ wakeTick(my) }, 800)
            return
        }
        wakeErr = false
        voice.listen({ text ->
            if (my == wakeGen) {
                val cmd = if (text.isNullOrBlank()) null else afterWake(text)
                if (cmd != null) {
                    wakeGen++
                    if (cmd.isBlank()) {
                        handsActive = Prefs.hands(this)
                        emptyListens = 0
                        listenNow()
                    } else {
                        submit(cmd)
                    }
                } else if (!wakeErr) {
                    main.postDelayed({ wakeTick(my) }, 400)
                }
            }
        }, { _ -> }, { err ->
            wakeErr = true
            transcript.add(Pair("note", "Wake word: $err"))
            main.postDelayed({ wakeTick(my) }, 5000)
        })
    }

    /** Returns what was said after "Orion", or null if the wake word was not said. */
    private fun afterWake(text: String): String? {
        val m = WAKE.find(text) ?: return null
        return text.substring(m.range.last + 1).trim().trimStart(',', '.', '!', ':', ' ')
    }

    // ---------------------------------------------------------------- AgentUi

    override fun say(text: String) {
        transcript.add(Pair("ai", text))
        main.post {
            if (running || listening) showStrip(text)
        }
        if (Prefs.voice(this)) voice.speak(text)
    }

    override fun status(text: String) {
        main.post {
            if (running) showStrip(text)
        }
    }

    override fun note(text: String) {
        transcript.add(Pair("note", text))
        main.post {
            if (running) showStrip(text)
        }
    }

    override fun phase(p: String) {
        phase = p
        refreshMode()
    }

    override fun ask(question: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        main.post {
            showConfirm(question) { yes ->
                answer.set(yes)
                latch.countDown()
            }
        }
        if (Prefs.voice(this)) voice.speak(question)
        latch.await()
        return answer.get()
    }

    // ---------------------------------------------------------------- tools: apps

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

    fun openApp(name: String): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val q = norm(name)
        if (q.isEmpty()) return "error: no app name given"
        var best: ResolveInfo? = null
        var bestLabel = ""
        for (ri in list) {
            val lbl = norm(ri.loadLabel(pm).toString())
            val pkg = ri.activityInfo.packageName.lowercase()
            if (lbl == q || pkg == name.trim().lowercase()) {
                best = ri
                bestLabel = ri.loadLabel(pm).toString()
                break
            }
            if (best == null && lbl.isNotEmpty() && (lbl.contains(q) || q.contains(lbl))) {
                best = ri
                bestLabel = ri.loadLabel(pm).toString()
            }
        }
        if (best == null) {
            val names = list.map { it.loadLabel(pm).toString() }.sorted().take(40)
            return "error: app not found. Installed apps include: " + names.joinToString(", ")
        }
        val launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: return "error: cannot launch $bestLabel"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        Thread.sleep(1800)
        return "opened $bestLabel"
    }

    fun openUrl(url: String): String {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        Thread.sleep(1500)
        return "opened $url"
    }

    // ---------------------------------------------------------------- tools: reading the screen

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "error: cannot read this screen (it may be protected)"
        nodes.clear()
        val size = screenSize()
        val sb = StringBuilder()
        sb.append("screen ${size.first}x${size.second}\n")
        walk(root, sb)
        if (nodes.size < 3) {
            sb.append("(very few elements: this may be a game or image screen. Use look or play.)\n")
        }
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= 80) return
        val text = n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString() ?: ""
        val lbl = if (n.isPassword) "(password field)" else text.trim()
        if (n.isVisibleToUser && (lbl.isNotEmpty() || n.isClickable || n.isEditable)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val id = nodes.size
                nodes.add(n)
                val kind = when {
                    n.isEditable -> "field"
                    n.isClickable -> "button"
                    n.isCheckable -> if (n.isChecked) "checked" else "unchecked"
                    else -> "text"
                }
                sb.append("[$id] $kind \"${lbl.take(60)}\" @${r.centerX()},${r.centerY()}\n")
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            if (c != null) walk(c, sb)
        }
    }

    fun clickId(id: Int): String {
        val n = nodes.getOrNull(id) ?: return "error: no element $id. Call read_screen first."
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Thread.sleep(600)
                return "clicked $id"
            }
            cur = cur.parent
            hops++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            if (c != null) {
                val f = findEditable(c)
                if (f != null) return f
            }
        }
        return null
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "error: cannot read this screen"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "error: no text field found. Click a text field first."
        if (field.isPassword) return "error: that is a password field. Ask the user to type it themselves."
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed" else "error: could not type there"
    }

    // ---------------------------------------------------------------- tools: touch

    private fun gesture(path: Path, durationMs: Long): String {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(30L, 4000L))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(g, cb, main)) return "error: the phone rejected the gesture"
        latch.await(6, TimeUnit.SECONDS)
        return if (ok.get()) "ok" else "error: gesture was cancelled"
    }

    fun tap(x: Float, y: Float): String {
        val size = screenSize()
        if (x < 0 || y < 0 || x > size.first || y > size.second) {
            return "error: ($x,$y) is outside the screen ${size.first}x${size.second}"
        }
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60L)
    }

    fun longPress(x: Float, y: Float): String {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 900L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): String {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        return gesture(p, ms)
    }

    fun scroll(direction: String): String {
        val size = screenSize()
        val x = size.first / 2f
        val h = size.second.toFloat()
        return if (direction.lowercase() == "up") {
            swipe(x, h * 0.3f, x, h * 0.7f, 350L)
        } else {
            swipe(x, h * 0.7f, x, h * 0.3f, 350L)
        }
    }

    private fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun key(name: String): String {
        val ok = when (name.lowercase().trim()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "enter" -> pressEnter()
            else -> return "error: unknown key $name"
        }
        Thread.sleep(500)
        return if (ok) "ok" else "error: that key did not work"
    }

    // ---------------------------------------------------------------- tools: screenshot

    fun screenshotBase64(): Shot? {
        onMainWait { setOverlaysVisible(false) }
        Thread.sleep(150)
        try {
            for (attempt in 1..2) {
                val holder = arrayOfNulls<Bitmap>(1)
                val latch = CountDownLatch(1)
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    shotExec,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                holder[0] = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshot.hardwareBuffer.close()
                            } catch (e: Exception) {
                            }
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    }
                )
                latch.await(6, TimeUnit.SECONDS)
                val bmp = holder[0]
                if (bmp != null) {
                    val width = 540
                    val scale = bmp.width / width.toFloat()
                    val height = (bmp.height / scale).toInt()
                    val small = Bitmap.createScaledBitmap(bmp, width, height, true)
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    return Shot(b64, scale, width, height)
                }
                Thread.sleep(1100)
            }
            return null
        } finally {
            onMainWait { setOverlaysVisible(true) }
        }
    }
}
