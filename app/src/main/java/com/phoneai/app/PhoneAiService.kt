package com.phoneai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val shotExec = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var agent: Agent
    private lateinit var voice: Voice

    private val transcript = CopyOnWriteArrayList<Pair<String, String>>()
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var strip: LinearLayout? = null
    private var stripText: TextView? = null
    private var confirmView: LinearLayout? = null
    private var logBox: LinearLayout? = null
    private var logScroll: ScrollView? = null
    private var pendingConfirm: ((Boolean) -> Unit)? = null

    @Volatile
    private var running = false
    private var listening = false
    private var handsActive = false
    private var emptyListens = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        voice = Voice(this)
        agent = Agent(this, this)
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
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
        strip = null
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

    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams, onClick: () -> Unit) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = ox + dx
                    lp.y = oy + dy
                    wm.updateViewLayout(v, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onClick()
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

    // ---------------------------------------------------------------- floating button

    private fun showBubble() {
        if (bubble != null || running || panel != null) return
        val b = TextView(this)
        b.text = "AI"
        b.setTextColor(0xFF04150D.toInt())
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.background = rounded(0xFF3DDC97.toInt(), dp(26).toFloat())
        val lp = WindowManager.LayoutParams(dp(52), dp(52), OVERLAY, NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = dp(220)
        makeDraggable(b, lp) { openPanel() }
        wm.addView(b, lp)
        bubble = b
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

    // ---------------------------------------------------------------- chat panel

    /** Called by the setup screen. */
    fun showPanel() {
        main.post { openPanel() }
    }

    private fun addLine(kind: String, text: String) {
        val box = logBox ?: return
        val t = TextView(this)
        t.text = text
        t.textSize = if (kind == "note") 12f else 15f
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(4), 0, dp(4))
        when (kind) {
            "me" -> {
                t.setTextColor(0xFF04150D.toInt())
                t.background = rounded(0xFF3DDC97.toInt(), dp(16).toFloat())
                lp.gravity = Gravity.END
            }
            "ai" -> {
                t.setTextColor(0xFFE9EEF2.toInt())
                t.background = rounded(0xFF171C22.toInt(), dp(16).toFloat(), 0xFF212932.toInt())
                lp.gravity = Gravity.START
            }
            else -> {
                t.setTextColor(0xFF8A96A3.toInt())
                t.typeface = Typeface.MONOSPACE
            }
        }
        if (kind != "note") t.setPadding(dp(12), dp(8), dp(12), dp(8))
        box.addView(t, lp)
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openPanel() {
        if (panel != null || running) return
        hideBubble()
        hideStrip()
        val size = screenSize()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(0xFF12161B.toInt(), dp(20).toFloat(), 0xFF212932.toInt())
        root.setPadding(dp(12), dp(10), dp(12), dp(10))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = label("Phone AI", 17f, 0xFFE9EEF2.toInt())
        title.typeface = Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val fresh = label("New", 14f, 0xFF8A96A3.toInt())
        fresh.setPadding(dp(10), dp(6), dp(10), dp(6))
        fresh.setOnClickListener { newChat() }
        head.addView(fresh)
        val close = label("Close", 14f, 0xFF8A96A3.toInt())
        close.setPadding(dp(10), dp(6), dp(10), dp(6))
        close.setOnClickListener { closePanel(true) }
        head.addView(close)
        root.addView(head)

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        logBox = box
        logScroll = scroll
        if (transcript.isEmpty()) {
            addLine("ai", "Tell me what to do. Type it, or tap Mic and say it.")
        } else {
            for (item in transcript) addLine(item.first, item.second)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this)
        input.hint = "Message..."
        input.setHintTextColor(0xFF8A96A3.toInt())
        input.setTextColor(0xFFE9EEF2.toInt())
        input.background = rounded(0xFF171C22.toInt(), dp(18).toFloat(), 0xFF212932.toInt())
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
        val mic = pill("Mic", 0xFF171C22.toInt(), 0xFFE9EEF2.toInt()) {
            handsActive = Prefs.hands(this)
            emptyListens = 0
            closePanel(false)
            listenNow()
        }
        val micLp = LinearLayout.LayoutParams(WRAP, WRAP)
        micLp.setMargins(dp(8), 0, 0, 0)
        row.addView(mic, micLp)
        val send = pill("Send", 0xFF3DDC97.toInt(), 0xFF04150D.toInt()) {
            val t = input.text.toString()
            input.setText("")
            submit(t)
        }
        val sendLp = LinearLayout.LayoutParams(WRAP, WRAP)
        sendLp.setMargins(dp(8), 0, 0, 0)
        row.addView(send, sendLp)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            size.first - dp(24), (size.second * 0.55f).toInt(), OVERLAY, NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(12)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.addView(root, lp)
        panel = root
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
        logBox = null
        logScroll = null
        if (thenBubble) showBubble()
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
        row.background = rounded(0xF012161B.toInt(), dp(16).toFloat(), 0xFF3DDC97.toInt())
        row.setPadding(dp(14), dp(10), dp(10), dp(10))
        val t = label(text, 14f, 0xFFE9EEF2.toInt())
        t.maxLines = 3
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val stop = pill("Stop", 0xFFFF6B6B.toInt(), 0xFFFFFFFF.toInt()) { stopAll() }
        row.addView(stop)
        val size = screenSize()
        val lp = WindowManager.LayoutParams(
            size.first - dp(24), WRAP, OVERLAY, NOT_FOCUSABLE or NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(8)
        wm.addView(row, lp)
        strip = row
        stripText = t
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
        stripText = null
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
        box.background = rounded(0xFF191409.toInt(), dp(18).toFloat(), 0xFFFFB84D.toInt())
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.addView(label(question, 16f, 0xFFE9EEF2.toInt()))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val no = pill("No", 0xFF171C22.toInt(), 0xFFE9EEF2.toInt()) { answer(false) }
        val yes = pill("Yes, do it", 0xFF3DDC97.toInt(), 0xFF04150D.toInt()) { answer(true) }
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
        if (Prefs.key(this).isEmpty()) {
            Toast.makeText(this, "Open the Phone AI app and save your NVIDIA key first.", Toast.LENGTH_LONG).show()
            return
        }
        transcript.add(Pair("me", t))
        voice.stopSpeaking()
        voice.stopListening()
        listening = false
        closePanel(false)
        hideBubble()
        running = true
        showStrip("Working...")
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
        val last = transcript.lastOrNull { it.first == "ai" }?.second ?: "Done."
        showStrip(last)
        if (handsActive && Prefs.hands(this)) {
            voice.whenIdle { listenNow() }
        } else {
            handsActive = false
            showBubble()
            main.postDelayed({
                if (!running && !listening) hideStrip()
            }, 6000)
        }
    }

    private fun listenNow() {
        if (running) return
        listening = true
        hideBubble()
        showStrip("Listening...")
        voice.stopSpeaking()
        voice.listen({ text ->
            listening = false
            if (text.isNullOrBlank()) {
                emptyListens++
                if (handsActive && emptyListens < 3) {
                    main.postDelayed({ listenNow() }, 500)
                } else {
                    endHands()
                }
            } else {
                emptyListens = 0
                submit(text)
            }
        }, { partial ->
            stripText?.text = partial
        })
    }

    private fun endHands() {
        handsActive = false
        listening = false
        hideStrip()
        showBubble()
    }

    private fun stopAll() {
        handsActive = false
        agent.stopped = true
        voice.stopSpeaking()
        voice.stopListening()
        listening = false
        pendingConfirm?.invoke(false)
        if (running) {
            stripText?.text = "Stopping..."
        } else {
            hideStrip()
            showBubble()
        }
    }

    // ---------------------------------------------------------------- AgentUi

    override fun say(text: String) {
        transcript.add(Pair("ai", text))
        main.post {
            if (running || listening) showStrip(text)
        }
        if (Prefs.voice(this)) voice.speak(text)
    }

    override fun note(text: String) {
        transcript.add(Pair("note", text))
        main.post {
            if (running) showStrip(text)
        }
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
        val lbl = text.trim()
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
