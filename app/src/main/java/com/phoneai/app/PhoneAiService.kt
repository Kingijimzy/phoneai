package com.phoneai.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
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
    @Volatile private var orionRuntime: OrionRuntime? = null
    @Volatile private var decisionScheduler: OrionDecisionScheduler? = null
    @Volatile private var runtimeDrainScheduled = false
    @Volatile private var runtimeDrainRequested = false
    @Volatile private var runtimeRetryRunnable: Runnable? = null
    @Volatile private var runtimeRetryAtElapsed = 0L
    @Volatile private var lastCapabilityReport: OrionCapabilityAudit.Report? = null
    @Volatile private var approvedRoutineReplayName = ""
    @Volatile private var approvedRoutineReplayAt = 0L

    private val transcript = CopyOnWriteArrayList<Pair<String, String>>()
    private val nodes = ArrayList<AccessibilityNodeInfo>()
    /** OCR-only elements from the latest read_screen; bounds are in physical screen coordinates. */
    private val ocrElements = ArrayList<ScreenPerception.OcrItem>()
    private val visualElements = ArrayList<VisualElementDetector.Region>()

    private var bubble: OrbView? = null
    private var panel: LinearLayout? = null
    private var panelOrb: OrbView? = null
    private var panelStatus: TextView? = null
    private var stopBtnRef: TextView? = null
    private var activityView: TextView? = null
    private var activityRunnable: Runnable? = null
    private var micBtnRef: TextView? = null
    private var strip: LinearLayout? = null
    private var stripOrb: OrbView? = null
    private var stripText: TextView? = null
    private var confirmView: LinearLayout? = null
    private var logBox: LinearLayout? = null
    private var logScroll: ScrollView? = null
    private var pendingConfirm: ((Boolean) -> Unit)? = null
    @Volatile private var mainChatListener: PhoneAiService.MainChatListener? = null
    @Volatile private var mainChatActive = false

    interface MainChatListener {
        fun onMessage(kind: String, text: String)
        fun onAssistantDelta(text: String) {}
        fun onStatus(text: String, mode: OrbView.Mode)
        fun onAudioLevel(level: Float) {}
        fun onAudioAmplitude(amplitude: Float) {}
        fun onSpeechWord() {}
        fun onTranscriptRestored(messages: List<Pair<String, String>>) {}
        fun onChatEvents(events: List<OrionConversationRepository.ChatEvent>) {}
        /** Orion decided the request needs (or no longer needs) the tool agent; the UI should follow. */
        fun onModeSwitch(commandMode: Boolean) {}
        /** Open a safe HTTP(S) page in the chat-owned browser surface. */
        fun onOpenBrowser(url: String) {}
    }

    fun notifyModeSwitch(commandMode: Boolean) {
        main.post { mainChatListener?.onModeSwitch(commandMode) }
    }

    override fun openBrowser(url: String) {
        val safe = ChatBrowserUrlPolicy.normalize(url) ?: return
        main.post { mainChatListener?.onOpenBrowser(safe) }
    }

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
    /** Back off repeated speech-service failures so wake detection cannot hammer the recognizer. */
    private var wakeErrorStreak = 0
    @Volatile private var offlineWakeDetector: OfflineWakeWordDetector? = null
    @Volatile private var offlineWakeFailedForSession = false

    // ---------------------------------------------------------------- lifecycle

    // Keep standing orders alive even when no new window/accessibility event arrives.
    // StandingOrders owns each order's persisted interval; this trigger only wakes the queue.
    private val soulWatchTick = object : Runnable {
        override fun run() {
            val runtime = orionRuntime
            if (runtime != null &&
                runtime.enqueue(OrionRuntime.WorkType.SOUL_WATCH, "soul-watch-periodic", 2)) {
                scheduleRuntimeDrain()
            }
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    private fun scheduleSoulWatchTick() {
        main.removeCallbacks(soulWatchTick)
        main.postDelayed(soulWatchTick, 60_000L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        voice = Voice(this)
        voice.onSpeaking = { s ->
            speaking = s
            refreshMode()
        }
        voice.onWord = {
            for (o in orbs()) o.pulseMouth()
            mainChatListener?.onSpeechWord()
        }
        voice.onLevel = { l ->
            for (o in orbs()) o.level = l
            mainChatListener?.onAudioLevel(l)
        }
        voice.onAudioAmplitude = { amplitude ->
            mainChatListener?.onAudioAmplitude(amplitude)
        }
        voice.onProblem = { message ->
            appendTranscript("note", "Voice output: $message")
            main.post {
                mainChatListener?.onMessage("note", "Voice output: $message")
                if (panel == null && !mainChatActive) showStrip("Voice output unavailable: $message")
            }
        }
        transcript.clear()
        transcript.addAll(ChatHistoryStore.load(this))
        agent = Agent(this, this)
        val runtime = OrionRuntime(this)
        orionRuntime = runtime
        runtime.start()
        val scheduler = OrionDecisionScheduler(runtime)
        decisionScheduler = scheduler
        scheduler.onServiceReady()
        scheduleRuntimeDrain()
        scheduleSoulWatchTick()
        showBubble()
        startWake()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || orionRuntime == null) return
        // Window transitions are meaningful state changes; ignore noisy text/content events.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Android may interrupt and later resume an accessibility service without recreating it.
            // Resume the bounded local queue only when a fresh real window event arrives.
            if (orionRuntime?.snapshot()?.state == OrionRuntime.State.PAUSED) orionRuntime?.resume()
            decisionScheduler?.onWindowChanged(event.packageName?.toString())
            scheduleRuntimeDrain()
        }
    }

    override fun onInterrupt() {
        orionRuntime?.pause("accessibility_interrupted")
    }

    private fun scheduleRuntimeDrain() {
        if (runtimeDrainScheduled) {
            runtimeDrainRequested = true
            return
        }
        runtimeDrainScheduled = true
        worker.execute {
            try {
                // Bounded event-driven drain; no polling loop and no provider/network calls.
                repeat(4) {
                    val runtime = orionRuntime ?: return@repeat
                    val item = runtime.claimNext() ?: return@repeat
                    try {
                        when (item.type) {
                            OrionRuntime.WorkType.RESTORE_PENDING -> {
                                if (::agent.isInitialized) agent.restoreConversationContext(ChatHistoryStore.load(this))
                                else throw IllegalStateException("agent_not_ready")
                            }
                            OrionRuntime.WorkType.CAPABILITY_RECHECK -> {
                                if (!::agent.isInitialized) {
                                    throw IllegalStateException("agent_not_ready")
                                }
                                val report = OrionCapabilityAudit.evaluate(
                                    registeredTools = ToolRegistry.all.size,
                                    implementedPacks = AgentPolicy.packs.count { it.implemented },
                                    enabledPacks = AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(this, it.id) },
                                    accessibilityReady = serviceInfo != null,
                                    screenDisclosureAccepted = Prefs.accessibilityDisclosureAccepted(this),
                                    // The local screen reader combines accessibility nodes, OCR and visual regions;
                                    // this is availability, not a claim that every screen is readable.
                                    screenPerceptionReady = serviceInfo != null && rootInActiveWindow != null
                                )
                                lastCapabilityReport = report
                                if (report.status == OrionCapabilityAudit.Status.BLOCKED) {
                                    throw IllegalStateException("capability_audit_blocked:" + report.limitations.joinToString(","))
                                }
                                // LIMITED is a valid, honest state: the user may not have accepted screen access
                                // or enabled a pack. Never auto-enable either to make the audit look healthy.
                            }
                            OrionRuntime.WorkType.SOUL_WATCH -> {
                                // Opt-in, read-only watcher: standing orders plus connected-service checks; no AI call.
                                // Connector failures back off via the runtime; standing orders always run.
                                SoulWatch.run(this)
                            }
                            OrionRuntime.WorkType.LEARN_FROM_RESULT -> {
                                // Agent has already persisted detailed success/failure evidence to ExperienceStore.
                                // This queue item records the outcome event and is acknowledged only when its key is valid.
                                if (!item.key.endsWith(":success") && !item.key.endsWith(":failure")) {
                                    throw IllegalArgumentException("invalid_task_outcome_event")
                                }
                            }
                        }
                        if (runtime.complete(item) && item.type == OrionRuntime.WorkType.SOUL_WATCH) {
                            OrionRuntimeNotifier.postResult(this, item, success = true)
                        }
                    } catch (error: Exception) {
                        val retryQueued = runtime.fail(item, error.javaClass.simpleName + ": " + error.message.orEmpty())
                        if (!retryQueued && item.type == OrionRuntime.WorkType.SOUL_WATCH) {
                            OrionRuntimeNotifier.postResult(this, item, success = false)
                        }
                    }
                }
            } finally {
                runtimeDrainScheduled = false
                // Only schedule again when a real event arrived during processing. A delayed
                // retry must wait for a future event, never spin in a tight retry loop.
                if (runtimeDrainRequested) {
                    runtimeDrainRequested = false
                    scheduleRuntimeDrain()
                } else {
                    scheduleRuntimeRetry(orionRuntime?.nextEligibleDelayMs())
                }
            }
        }
    }

    /** Keep exactly one monotonic wake-up, replacing it if a newly queued retry is due sooner. */
    private fun scheduleRuntimeRetry(delayMs: Long?) {
        main.post {
            if (delayMs == null) {
                runtimeRetryRunnable?.let { main.removeCallbacks(it) }
                runtimeRetryRunnable = null
                runtimeRetryAtElapsed = 0L
                return@post
            }
            val delay = delayMs.coerceAtLeast(1L)
            val deadline = SystemClock.uptimeMillis() + delay
            val existing = runtimeRetryRunnable
            if (existing != null && runtimeRetryAtElapsed <= deadline) return@post
            existing?.let { main.removeCallbacks(it) }
            lateinit var next: Runnable
            next = Runnable {
                if (runtimeRetryRunnable === next) {
                    runtimeRetryRunnable = null
                    runtimeRetryAtElapsed = 0L
                    scheduleRuntimeDrain()
                }
            }
            runtimeRetryRunnable = next
            runtimeRetryAtElapsed = deadline
            main.postDelayed(next, delay)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(soulWatchTick)
        orionRuntime?.stop()
        runtimeRetryRunnable?.let { main.removeCallbacks(it) }
        runtimeRetryRunnable = null
        runtimeRetryAtElapsed = 0L
        orionRuntime = null
        decisionScheduler = null
        instance = null
        wakeGen++
        stopOfflineWakeDetector()
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

    fun replayScreenSize(): Pair<Int, Int> = screenSize()

    /** Called only after the saved-routine preview dialog's explicit Replay confirmation. */
    fun replayConfirmedRoutineFromUi(name: String) {
        approvedRoutineReplayName = MacroStore.norm(name)
        approvedRoutineReplayAt = SystemClock.uptimeMillis()
        sendFromMainChat("run routine $name")
    }

    internal fun consumeRoutineReplayApproval(name: String): Boolean {
        val accepted = approvedRoutineReplayName == MacroStore.norm(name) &&
            SystemClock.uptimeMillis() - approvedRoutineReplayAt in 0L..30_000L
        approvedRoutineReplayName = ""
        approvedRoutineReplayAt = 0L
        return accepted
    }

    /** Bring the exact recorded target package forward; caller still verifies its screen before tapping. */
    fun openRoutineTarget(packageName: String): String {
        val target = packageName.trim()
        if (!Regex("^[A-Za-z0-9_.]{3,255}$").matches(target)) return "error: saved routine has no valid target app package"
        if (currentPackageName().equals(target, true)) return "ok"
        return try {
            val intent = packageManager.getLaunchIntentForPackage(target)
                ?: return "error: the recorded target app is not installed or has no launchable activity"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            "ok"
        } catch (e: Exception) {
            "error: could not open the recorded target app (" + (e.message ?: e.javaClass.simpleName) + ")"
        }
    }

    fun waitForRoutinePackage(packageName: String, timeoutMs: Long = 6_000L, stopped: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs.coerceIn(0L, 15_000L)
        while (SystemClock.uptimeMillis() < end) {
            if (stopped()) return false
            if (currentPackageName().equals(packageName, true)) return true
            Thread.sleep(100L)
        }
        return currentPackageName().equals(packageName, true)
    }

    /** One immediate local comparison used to disambiguate an interrupted recorded gesture. */
    fun checkRecordedSignature(x: Int, y: Int, expected: IntArray): String {
        if (expected.size != 108) return "invalid"
        val bitmap = grabBitmap() ?: return "unavailable"
        return try {
            if (sigDistance(signature(bitmap, x, y), expected) <= 32.0) "match" else "mismatch"
        } catch (_: Exception) {
            "unavailable"
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

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
        t.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(75L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(125L).start()
            }
            false
        }
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
        t.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(75L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(125L).start()
            }
            false
        }
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
    private fun refreshMode() {
        main.post {
            val m = when {
                speaking -> OrbView.Mode.SPEAKING
                confirmView != null -> OrbView.Mode.WAITING_APPROVAL
                listening -> OrbView.Mode.LISTENING
                phase == "error" -> OrbView.Mode.ERROR
                running -> if (phase == "acting") OrbView.Mode.ACTING else OrbView.Mode.THINKING
                else -> OrbView.Mode.IDLE
            }
            for (o in orbs()) o.mode = m
            mainChatListener?.onStatus(
                when (m) {
                    OrbView.Mode.IDLE -> "Ready when you are"
                    OrbView.Mode.LISTENING -> "Listening…"
                    OrbView.Mode.THINKING -> "Thinking through it…"
                    OrbView.Mode.SPEAKING -> "Speaking…"
                    OrbView.Mode.ACTING -> "Working on your request…"
                    OrbView.Mode.WAITING_APPROVAL -> "Waiting for your approval"
                    OrbView.Mode.ERROR -> "Task paused — check the last step"
                }, m
            )
            micBtnRef?.text = if (listening) "Stop" else "Mic"
            stopBtnRef?.visibility = if (running) View.VISIBLE else View.GONE
            panelStatus?.text = when (m) {
                OrbView.Mode.IDLE -> if (Prefs.wake(this)) "Say \"Orion\" or tap the mic" else "Ready"
                OrbView.Mode.LISTENING -> "Listening..."
                OrbView.Mode.THINKING -> "Thinking..."
                OrbView.Mode.SPEAKING -> "Speaking..."
                OrbView.Mode.ACTING -> "Working on it..."
                OrbView.Mode.WAITING_APPROVAL -> "Waiting for your approval"
                OrbView.Mode.ERROR -> "Task paused — check the last step"
            }
        }
    }

    // ---------------------------------------------------------------- floating button

    private fun showBubble() {
        if (bubble != null || running || panel != null) return
        val b = OrbView(this)
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

    fun openMainChat(pickImage: Boolean = false) {
        main.post {
            closePanel(false)
            hideBubble()
            try {
                startActivity(Intent(this, MainChatActivity::class.java).putExtra(MainChatActivity.EXTRA_PICK_IMAGE, pickImage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Orion chat. Try opening it from the app home screen.", Toast.LENGTH_LONG).show()
                showBubble()
            }
        }
    }

    fun attachMainChat(listener: MainChatListener) {
        main.post {
            stopOfflineWakeDetector()
            // A visible chat/live-call surface owns microphone interaction. Cancel any
            // background wake recognizer so it cannot compete with the foreground mic.
            wakeGen++
            if (!listening) voice.stopListening()
            mainChatListener = listener
            mainChatActive = true
            if (panel != null) closePanel(false)
            hideBubble()
            listener.onStatus(if (running) "Orion is working…" else "Ready when you are", currentMode())
            if (transcript.isEmpty()) {
                listener.onMessage("ai", "Hi. I’m Orion. What are we working on today?")
            } else {
                transcript.forEach { listener.onMessage(it.first, it.second) }
            }
            listener.onChatEvents(activeChatEvents())
        }
    }

    fun detachMainChat(listener: MainChatListener) {
        main.post {
            if (mainChatListener === listener) {
                mainChatListener = null
                mainChatActive = false
                if (!running && panel == null) showBubble()
                startWake()
            }
        }
    }

    /** Clears the active transcript and the in-memory agent chat context. */
    fun clearActiveConversation(): Boolean {
        if (running || imageAnalysisRunning) return false
        val persisted = try {
            getSharedPreferences("orion_chat_history", MODE_PRIVATE)
                .edit().remove("active_transcript").commit()
        } catch (_: Exception) { false }
        if (!persisted) return false
        transcript.clear()
        try { agent.resetConversationContext() } catch (_: Exception) { }
        main.post {
            mainChatListener?.onTranscriptRestored(emptyList())
            mainChatListener?.onStatus("Conversation cleared", currentMode())
        }
        return true
    }

    /** Persist only bounded, secret-free activity metadata in the active chat session. */
    fun recordChatEvent(type: String, status: String, summary: String, messageId: String? = null) {
        try {
            val repository = OrionConversationRepository.get(this)
            repository.recordEvent(this, messageId, type, status, summary)
            // Persist first, then publish the canonical session event list to the open chat.
            // This keeps tool/approval status visible immediately and restores it after reattach.
            val events = repository.eventsForActive(this)
            main.post { mainChatListener?.onChatEvents(events) }
        } catch (_: Exception) {
            /* Conversation storage must never crash phone control. */
        }
    }

    fun activeChatEvents(): List<OrionConversationRepository.ChatEvent> =
        try { OrionConversationRepository.get(this).eventsForActive(this) } catch (_: Exception) { emptyList() }

    private fun rewindTranscriptToPrompt(prompt: String): Boolean {
        val rewound = TranscriptEditPolicy.rewind(transcript.toList(), prompt) ?: return false
        transcript.clear()
        transcript.addAll(rewound)
        ChatHistoryStore.save(this, transcript.toList())
        agent.reset()
        agent.restoreConversationContext(transcript.toList())
        mainChatListener?.onTranscriptRestored(transcript.toList())
        return true
    }

    fun retryLastResponse(prompt: String) {
        main.post {
            if (running) {
                mainChatListener?.onMessage("ai", "Stop the current response before retrying.")
                return@post
            }
            if (!rewindTranscriptToPrompt(prompt)) {
                mainChatListener?.onMessage("ai", "I couldn't match that message to the saved conversation, so I did not retry it. Send it again manually.")
                return@post
            }
            submit(prompt, conversationOnly = true, autoRoute = true)
        }
    }

    fun editAndResend(original: String, edited: String) {
        val replacement = edited.trim()
        if (replacement.isEmpty()) return
        main.post {
            if (running) {
                mainChatListener?.onMessage("ai", "Stop the current response before editing this message.")
                return@post
            }
            if (!rewindTranscriptToPrompt(original)) {
                mainChatListener?.onMessage("ai", "I couldn't match that message to the saved conversation, so I did not replace it. Copy the text into a new message instead.")
                return@post
            }
            submit(replacement, conversationOnly = true, autoRoute = true)
        }
    }

    fun sendFromMainChat(
        text: String,
        conversationOnly: Boolean = false,
        autoRoute: Boolean = false
    ) {
        main.post {
            mainChatActive = mainChatListener != null
            submit(text, conversationOnly, autoRoute)
        }
    }

    /**
     * Explicit live-call or specialist-agent handoff into the shared, guarded tool executor.
     * This is not a permission bypass: Agent dispatch still checks tool modes, packs, Android
     * grants, and action-specific confirmation before each tool runs.
     */
    fun executeSharedToolRequest(text: String, source: String = "alternate surface", completion: ((String) -> Unit)? = null) {
        val command = text.trim()
        if (command.isEmpty()) return
        main.post {
            if (running) {
                val message = "Orion is already working. Let the current task finish, then retry this request."
                mainChatListener?.onMessage("ai", message)
                completion?.invoke(message)
                return@post
            }
            handsActive = false
            stopOfflineWakeDetector()
            appendTranscript("me", command)
            mainChatListener?.onMessage("me", command)
            mainChatListener?.onStatus("Tool request handed off from $source", currentMode())
            voice.stopSpeaking()
            voice.stopListening()
            listening = false
            running = true
            phase = "thinking"
            refreshMode()
            worker.execute {
                try {
                    agent.run(command)
                } catch (e: Exception) {
                    say("I couldn't complete that request. " + (e.message ?: e.javaClass.simpleName).take(180))
                } finally {
                    val result = transcript.lastOrNull { it.first == "ai" }?.second
                        ?: "The request finished without a final response. Check the task transcript."
                    main.post {
                        finishRun()
                        completion?.invoke(result)
                    }
                }
            }
        }
    }

    @Volatile private var imageAnalysisRunning = false
    private val imageAnalysisLock = Any()

    /** Decode gallery images across Android-supported formats (including HEIC/AVIF where supported), then fall back to BitmapFactory. */
    private fun decodeVisionBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val width = info.size.width.coerceAtLeast(1)
                    val height = info.size.height.coerceAtLeast(1)
                    val scale = minOf(1.0, 1024.0 / maxOf(width, height))
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
            } catch (_: Exception) {
                // Some content providers expose formats ImageDecoder cannot read; try the classic decoder.
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("The selected image could not be opened. Choose a photo saved on the device, or take a screenshot.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Android could not decode this image format. Try exporting it as JPG or PNG.")
        }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Android couldn't decode the selected image. Try a JPG or PNG copy.")
    }

    /** Single-image compatibility entry point. */
    fun analyzeImageFromMainChat(uri: Uri, question: String) = analyzeImagesFromMainChat(listOf(uri), question)

    /** Analyse up to four selected images in one request so they can be compared together. */
    fun analyzeImagesFromMainChat(uris: List<Uri>, question: String) {
        val images = uris.distinct().take(4)
        if (images.isEmpty()) return
        synchronized(imageAnalysisLock) {
            if (running || imageAnalysisRunning) {
                main.post { mainChatListener?.onMessage("ai", "Orion is already working on another task. Wait for it to finish before analysing these images.") }
                return
            }
            imageAnalysisRunning = true
        }
        main.post {
            mainChatActive = mainChatListener != null
            mainChatListener?.onStatus("Analysing " + images.size + " image(s)", OrbView.Mode.THINKING)
        }
        Thread({
            try {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", question))
                var totalBytes = 0
                images.forEachIndexed { index, uri ->
                    val bitmap = decodeVisionBitmap(uri)
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
                    bitmap.recycle()
                    if (bytes.size() == 0) throw IllegalArgumentException("Image " + (index + 1) + " converted to an empty file. Try JPG or PNG.")
                    totalBytes += bytes.size()
                    if (totalBytes > 8 * 1024 * 1024) throw IllegalArgumentException("The selected images exceed the combined 8 MB upload budget. Remove an image or choose smaller screenshots.")
                    val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    content.put(JSONObject().put("type", "text").put("text", "Image " + (index + 1) + " of " + images.size))
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + encoded)))
                }
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "You are Orion, a helpful visual assistant. Analyse all supplied images together when relevant. Describe only what is visible, distinguish observation from inference, and never claim details that cannot be seen."))
                    .put(JSONObject().put("role", "user").put("content", content))
                val reply = Nim(this).chat(messages, true, 520, 0.2).trim()
                main.post {
                    mainChatListener?.onMessage("ai", if (reply.isBlank()) "I could not get a useful answer from those images. Please try again." else reply)
                    mainChatListener?.onStatus("Image analysis complete", OrbView.Mode.IDLE)
                }
            } catch (e: Exception) {
                val reason = (e.message ?: "unknown error").take(320)
                val guidance = when {
                    reason.contains("No AI key", true) || reason.contains("key saved", true) -> " Open Settings → AI providers and save a key for a vision-capable provider."
                    reason.contains("vision model", true) || reason.contains("no vision", true) || reason.contains("supports this request type", true) -> " The selected model may be text-only. Choose a provider/model that explicitly supports image input."
                    reason.contains("429", true) || reason.contains("limit", true) || reason.contains("credit", true) -> " The provider may be out of free quota or credit. Review the selected provider or retry after the limit resets."
                    reason.contains("503", true) || reason.contains("temporarily busy", true) || reason.contains("service unavailable", true) -> " This is usually a temporary provider outage. Check the selected provider/model or retry later."
                    reason.contains("400", true) || reason.contains("422", true) || reason.contains("image", true) || reason.contains("unsupported", true) -> " The provider may have rejected the image format or model. Try smaller JPG/PNG images and verify vision support."
                    else -> " Check internet access and the vision provider/model in Settings → AI providers."
                }
                main.post {
                    val visionProvider = Prefs.provider(this, true)
                    val visionModel = Prefs.model(this, visionProvider, true)
                    mainChatListener?.onMessage("ai", "Image analysis failed. Selected vision model: " + visionProvider.label + " / " + visionModel + ". Details: " + reason + "." + guidance + " Check Settings → AI providers → Vision model.")
                    mainChatListener?.onStatus("Image analysis failed · see details", OrbView.Mode.IDLE)
                }
            } finally {
                synchronized(imageAnalysisLock) { imageAnalysisRunning = false }
            }
        }, "Orion-image-analysis").start()
    }

    fun listenFromMainChat() {
        main.post {
            if (!running && !listening) {
                mainChatActive = mainChatListener != null
                handsActive = false
                emptyListens = 0
                listenNow()
            }
        }
    }

    fun stopVoiceInput() {
        main.post {
            if (!listening) return@post
            handsActive = false
            emptyListens = 0
            voice.stopListening()
            listening = false
            refreshMode()
            if (panel == null) {
                hideStrip()
                showBubble()
                startWake()
            }
        }
    }

    fun endLiveConversation() {
        main.post {
            handsActive = false
            emptyListens = 0
            if (listening) voice.stopListening()
            listening = false
            voice.stopSpeaking()
            refreshMode()
            if (panel == null) {
                hideStrip()
                showBubble()
                startWake()
            }
        }
    }


    fun restoreArchivedChat(messages: List<Pair<String, String>>) {
        main.post {
            if (running) {
                mainChatListener?.onMessage("ai", "Let the current task finish or stop it before switching conversations.")
                return@post
            }
            transcript.clear()
            transcript.addAll(messages.filter { it.first == "me" || it.first == "ai" }.takeLast(80))
            ChatHistoryStore.restoreActive(this, transcript)
            agent.reset()
            agent.restoreConversationContext(transcript.toList())
            mainChatListener?.onTranscriptRestored(transcript.toList())
            mainChatListener?.onStatus("Previous conversation restored", OrbView.Mode.IDLE)
        }
    }

    fun restoreArchivedChat(sessionId: String) {
        main.post {
            if (running) {
                mainChatListener?.onMessage("ai", "Let the current task finish or stop it before switching conversations.")
                return@post
            }
            val restored = ChatHistoryStore.restoreArchivedById(this, sessionId)
            if (restored == null) {
                mainChatListener?.onMessage("ai", "That archived chat is no longer available. Refresh history and try again.")
                return@post
            }
            transcript.clear()
            transcript.addAll(restored.filter { it.first == "me" || it.first == "ai" }.takeLast(80))
            agent.reset()
            agent.restoreConversationContext(transcript.toList())
            mainChatListener?.onTranscriptRestored(transcript.toList())
            mainChatListener?.onStatus("Previous conversation restored", OrbView.Mode.IDLE)
        }
    }

    fun startNewChat() {
        main.post {
            if (running) {
                mainChatListener?.onMessage("ai", "Stop or finish the current task before starting a new chat.")
                return@post
            }
            ChatHistoryStore.archiveAndClear(this, transcript.toList())
            transcript.clear()
            agent.reset()
            mainChatListener?.onTranscriptRestored(emptyList())
            mainChatListener?.onStatus("New chat ready", OrbView.Mode.IDLE)
        }
    }

    /** Permanently clear the active transcript and archived chats after the user confirms in the UI. */
    fun deleteAllChatHistory(onResult: ((deleted: Boolean, message: String) -> Unit)? = null) {
        main.post {
            if (running) {
                val message = "Stop or finish the current task before deleting chat history."
                mainChatListener?.onMessage("ai", message)
                onResult?.invoke(false, message)
                return@post
            }
            if (!ChatHistoryStore.clearAll(this)) {
                val message = "Chat history could not be cleared from storage. No deletion was confirmed."
                mainChatListener?.onMessage("note", message)
                onResult?.invoke(false, message)
                return@post
            }
            transcript.clear()
            agent.reset()
            mainChatListener?.onTranscriptRestored(emptyList())
            mainChatListener?.onStatus("Chat history deleted", OrbView.Mode.IDLE)
            val message = "Chat history deleted"
            mainChatListener?.onMessage("note", "Active and archived chat history deleted from this device.")
            onResult?.invoke(true, message)
        }
    }

    fun resetMainChat() {
        main.post {
            if (!running) {
                agent.reset()
                ChatHistoryStore.archiveAndClear(this, transcript)
                transcript.clear()
                mainChatListener?.onMessage("ai", "New conversation. What are we working on?")
            }
        }
    }

    fun displayMode(): OrbView.Mode = currentMode()

    private fun currentMode(): OrbView.Mode = when {
        speaking -> OrbView.Mode.SPEAKING
        confirmView != null -> OrbView.Mode.WAITING_APPROVAL
        listening -> OrbView.Mode.LISTENING
        running -> if (phase == "acting") OrbView.Mode.ACTING else OrbView.Mode.THINKING
        else -> OrbView.Mode.IDLE
    }

    /** Snapshot of the persisted conversation for Agent restoration; UI notes/status never enter AI context. */
    fun conversationContext(): List<Pair<String, String>> =
        transcript.filter { it.first == "me" || it.first == "ai" }.takeLast(12).toList()

    internal fun onAgentTaskFinished(task: String, success: Boolean) {
        decisionScheduler?.onTaskFinished(task, success)
        scheduleRuntimeDrain()
    }

    private fun appendTranscript(kind: String, text: String) {
        transcript.add(Pair(kind, text))
        if (kind == "me" || kind == "ai") ChatHistoryStore.save(this, transcript)
    }

    private fun addLine(kind: String, text: String) {
        val box = logBox ?: return
        val t = TextView(this)
        t.text = text
        t.textSize = if (kind == "note") 11f else 15f
        t.setLineSpacing(dp(2).toFloat(), 1.02f)
        t.maxWidth = (screenSize().first * 0.82f).toInt()
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.setMargins(0, dp(5), 0, dp(5))
        when (kind) {
            "me" -> {
                t.setTextColor(Theme.TEXT)
                t.background = rounded(0xFF292929.toInt(), dp(18).toFloat(), 0xFF454545.toInt())
                lp.gravity = Gravity.END
                lp.setMargins(dp(36), dp(7), 0, dp(7))
            }
            "ai" -> {
                // Grok-inspired reading flow: assistant answers sit directly on the canvas,
                // while only user turns are enclosed in a distinct bubble.
                t.setTextColor(Theme.TEXT)
                t.background = rounded(Theme.BG, dp(8).toFloat())
                lp.gravity = Gravity.START
                lp.setMargins(0, dp(8), dp(20), dp(8))
            }
            else -> {
                t.setTextColor(Theme.MUTED)
                t.typeface = Typeface.MONOSPACE
            }
        }
        if (kind != "note") {
            if (kind == "me") t.setPadding(dp(13), dp(10), dp(13), dp(10))
            else t.setPadding(dp(3), dp(7), dp(3), dp(7))
            t.contentDescription = if (kind == "me") "Your message. Long press to copy." else "Orion reply. Long press to copy."
            t.setOnLongClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Orion message", text))
                Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show()
                true
            }
        }
        t.alpha = 0f
        t.translationY = dp(if (kind == "me") 8 else 10).toFloat()
        box.addView(t, lp)
        t.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(210L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showThinkingIndicator() {
        hideThinkingIndicator()
        val box = logBox ?: return
        val indicator = label("Orion is thinking", 13f, Theme.CYAN)
        indicator.setPadding(dp(12), dp(9), dp(12), dp(9))
        indicator.background = rounded(Theme.PANEL, dp(15).toFloat(), Theme.LINE)
        indicator.contentDescription = "Orion is generating a response"
        box.addView(indicator, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            setMargins(0, dp(5), 0, dp(5))
        })
        activityView = indicator
        var frame = 0
        val ticker = object : Runnable {
            override fun run() {
                if (activityView !== indicator || panel == null || !running) return
                frame = (frame + 1) % 4
                indicator.text = "Orion is thinking" + "·".repeat(frame)
                val pulse = if (frame % 2 == 0) 1f else 0.76f
                val scale = if (frame % 2 == 0) 1f else 0.985f
                indicator.animate().alpha(pulse).scaleX(scale).scaleY(scale).setDuration(340L).start()
                logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
                main.postDelayed(this, 420)
            }
        }
        activityRunnable = ticker
        main.postDelayed(ticker, 420)
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideThinkingIndicator() {
        activityRunnable?.let { main.removeCallbacks(it) }
        activityRunnable = null
        activityView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        activityView = null
    }

    private fun openPanel() {
        if (panel != null || running) return
        stopOfflineWakeDetector()
        wakeGen++
        hideBubble()
        hideStrip()
        val size = screenSize()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF202020.toInt(), 0xFF111111.toInt(), 0xFF090909.toInt())).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0xFF414141.toInt())
        }
        root.setPadding(dp(12), dp(10), dp(12), dp(12))
        root.elevation = dp(18).toFloat()

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF292929.toInt(), 0xFF191919.toInt(), 0xFF141414.toInt())).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0xFF3E3E3E.toInt())
        }
        head.setPadding(dp(8), dp(5), dp(6), dp(5))
        val orb = OrbView(this)
        orb.contentDescription = "Orion animated character. Tap to speak."
        orb.isClickable = true
        orb.setOnClickListener {
            if (!running && !listening) {
                handsActive = Prefs.hands(this)
                emptyListens = 0
                listenNow()
            }
        }
        head.addView(orb, LinearLayout.LayoutParams(dp(56), dp(56)))
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.setPadding(dp(8), 0, 0, 0)
        val title = label("ORION", 16f, Theme.TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        titleCol.addView(title)
        val st = label("PERSONAL AI  ·  READY TO HELP", 11f, Theme.CYAN)
        titleCol.addView(st)
        val tagline = label("Think with you. Act with permission.", 10f, Theme.MUTED)
        titleCol.addView(tagline)
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        val stop = label("■ Stop", 12f, Theme.WARN)
        stop.setPadding(dp(8), dp(6), dp(8), dp(6))
        stop.setOnClickListener { stopAll() }
        stop.visibility = View.GONE
        head.addView(stop)
        stopBtnRef = stop
        val fresh = label("＋ New", 13f, Theme.MUTED)
        fresh.setPadding(dp(10), dp(6), dp(10), dp(6))
        fresh.setOnClickListener { newChat() }
        head.addView(fresh)
        val close = label("×", 22f, Theme.MUTED)
        close.setPadding(dp(12), dp(4), dp(10), dp(4))
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
            "Read screen" to "read my screen",
            "AI status" to "ai status",
            "Network status" to "network status"
        )
        val liveChip = chip("Voice command") { startLive() }
        val llp = LinearLayout.LayoutParams(WRAP, WRAP)
        llp.setMargins(0, 0, dp(6), 0)
        chips.addView(liveChip, llp)
        val fullChatChip = chip("Full chat  ↗") { openMainChat() }
        val fullChatLp = LinearLayout.LayoutParams(WRAP, WRAP)
        fullChatLp.setMargins(0, 0, dp(6), 0)
        chips.addView(fullChatChip, fullChatLp)
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
        input.hint = "Quick command for your phone…"
        input.setHintTextColor(Theme.MUTED)
        input.setTextColor(Theme.TEXT)
        input.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF242424.toInt(), 0xFF151515.toInt())).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0xFF414141.toInt())
        }
        input.setPadding(dp(15), dp(10), dp(15), dp(10))
        input.minHeight = dp(46)
        input.setSingleLine(true)
        input.maxLines = 1
        input.ellipsize = android.text.TextUtils.TruncateAt.END
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ ->
            val t = input.text.toString()
            input.setText("")
            submit(t)
            true
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
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
        val micLp = LinearLayout.LayoutParams(dp(52), dp(46))
        micLp.setMargins(dp(8), 0, 0, 0)
        row.addView(micBtn, micLp)
        micBtnRef = micBtn
        val imageBtn = pill("＋", Theme.PANEL2, Theme.TEXT) {
            // Android's document picker belongs to an Activity; hand off to full chat.
            openMainChat(true)
        }
        imageBtn.contentDescription = "Choose an image for Orion to analyse"
        val imageLp = LinearLayout.LayoutParams(dp(42), dp(46))
        imageLp.setMargins(dp(6), 0, 0, 0)
        row.addView(imageBtn, imageLp)
        val send = pill("Send", Theme.CYAN, Theme.ON_ACCENT) {
            val t = input.text.toString()
            input.setText("")
            submit(t)
        }
        val sendLp = LinearLayout.LayoutParams(dp(66), dp(46))
        sendLp.setMargins(dp(8), 0, 0, 0)
        row.addView(send, sendLp)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            size.first - dp(16), (size.second * 0.70f).toInt(), OVERLAY, NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(12)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        root.alpha = 0f
        root.translationY = dp(24).toFloat()
        wm.addView(root, lp)
        panel = root
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(230L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // Stagger the panel's main surfaces so the overlay feels responsive rather than static.
        listOf<View>(head, scroll, chipScroll, row).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(7).toFloat()
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay((index * 45L) + 35L)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
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
        stopBtnRef = null
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
        ChatHistoryStore.archiveAndClear(this, transcript)
        transcript.clear()
        hideThinkingIndicator()
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
        row.background = rounded(0xF002080A.toInt(), dp(22).toFloat(), Theme.CYAN)
        row.setPadding(dp(8), dp(6), dp(10), dp(6))
        val orb = OrbView(this)
        row.addView(orb, LinearLayout.LayoutParams(dp(52), dp(52)))
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
        if (mainChatActive) {
            mainChatListener?.onStatus(text, OrbView.Mode.LISTENING)
        } else if (panel != null) {
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

    /** Multi-choice ask_user surface. The access badge is explicit and no option is preselected. */
    private fun showUserChoices(question: String, options: List<String>, access: String, cb: (String) -> Unit) {
        removeConfirm()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF111A20.toInt(), dp(20).toFloat(), Theme.CYAN)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(label("ORION NEEDS YOUR CHOICE", 11f, Theme.CYAN).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        val badgeText = when (access.uppercase()) {
            "WRITE", "WRITE DATA", "WRITE/CHANGE" -> "WRITE · MAY CHANGE DATA"
            else -> "READ · NO DATA CHANGES REQUESTED"
        }
        box.addView(label(badgeText, 10f, if (badgeText.startsWith("WRITE")) Theme.WARN else Theme.OK).apply {
            setPadding(0, dp(5), 0, dp(4))
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(label(question, 16f, Theme.TEXT).apply { setPadding(0, dp(6), 0, dp(10)) })
        options.forEach { option ->
            val choice = pill(option, Theme.PANEL2, Theme.TEXT) {
                removeConfirm()
                pendingConfirm = null
                refreshMode()
                cb(option)
            }
            val lp = LinearLayout.LayoutParams(-1, WRAP)
            lp.topMargin = dp(6)
            box.addView(choice, lp)
        }
        val cancel = pill("Cancel", Theme.PANEL, Theme.MUTED) {
            removeConfirm()
            pendingConfirm = null
            refreshMode()
            cb("")
        }
        val cancelLp = LinearLayout.LayoutParams(-1, WRAP)
        cancelLp.topMargin = dp(8)
        box.addView(cancel, cancelLp)
        val size = screenSize()
        val lp = WindowManager.LayoutParams(
            size.first - dp(24), WRAP, OVERLAY, NOT_FOCUSABLE or NOT_MODAL, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        wm.addView(box, lp)
        confirmView = box
        refreshMode()
    }

    private fun showConfirm(question: String, cb: (Boolean) -> Unit) {
        removeConfirm()
        val answer: (Boolean) -> Unit = { yes ->
            removeConfirm()
            pendingConfirm = null
            refreshMode()
            cb(yes)
        }
        pendingConfirm = answer
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(0xFF191409.toInt(), dp(20).toFloat(), Theme.WARN)
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.addView(label("APPROVAL REQUIRED", 11f, Theme.WARN).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        box.addView(label(question, 16f, Theme.TEXT).apply {
            setPadding(0, dp(7), 0, 0)
        })
        box.addView(label("Orion will wait here. Nothing in this step continues until you choose.", 12f, Theme.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val no = pill("Cancel action", Theme.PANEL2, Theme.TEXT) { answer(false) }
        val yes = pill("Approve & continue", Theme.CYAN, Theme.ON_ACCENT) { answer(true) }
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
        refreshMode()
    }

    // ---------------------------------------------------------------- running commands

    private fun isConversationStarter(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Regex("^(hey|hi|hello|good morning|good evening|let'?s talk|can we talk|i want to talk|talk to me|are you there|how are you|can i ask you something|i (also )?(have|got) a question)(\\b|$)")
            .containsMatchIn(normalized)
    }

    /**
     * Central chat intent router. UI surfaces pass the user's mode preference; this
     * service owns the decision about whether an explicit request needs the tool agent.
     * Keep live voice on its separate wake-phrase guard below.
     */
    private fun shouldAutoRouteToAgent(message: String): Boolean {
        val text = message.trim().lowercase(java.util.Locale.ROOT)
        if (text.isBlank()) return false
        // Deterministic research routing takes precedence over a model's unsupported claim that it lacks tools.
        if (OrionResearchRoutingPolicy.requiresToolAgent(text)) return true
        // Explicit runtime checks and named public-repository/site requests must reach
        // the guarded agent even when the chat is currently in Talk mode.
        if (ConversationAutoRoutePolicy.requiresToolAgent(text)) return true
        // Routing is deliberately conservative: a request must read like an instruction
        // or a direct question, not a sentence merely discussing an action.
        val patterns = listOf(
            Regex("^(\\s*(please\\s+)?(can you|could you|would you|will you)?\\s*(open|launch|start|close|switch to|tap|click|press|scroll|swipe|type into|enter into|navigate to)\\b)"),
            Regex("^(\\s*(please\\s+)?(i want you to|i need you to|help me)\\s+(open|launch|start|close|switch to|tap|click|press|scroll|swipe|type into|enter into|navigate to)\\b)"),
            Regex("^(\\s*(please\\s+)?(can you|could you|would you|please)?\\s*(search (the )?web|look up|google|browse to|visit (the )?website|go to (the )?website|find (online|on the web)|research (online|the latest))\\b)"),
            Regex("^(what('?s| is)\\s+(the )?(current |latest |today'?s |live )?(weather|news|price|rate|score|forecast|exchange rate|market|time in|model|release|version)\\b)"),
            Regex("^(what is|what's|check|find|show me|compare|look up)\\b.*\\b(current price|price of|price|weather|news|exchange rate|market price|latest update|live score|forecast)\\b"),
            Regex("^(check|look up|find|show me)\\b.*\\b([a-z0-9]{2,}(usdt|usd|btc|eth)?|bitcoin|ethereum|btc|eth)('?s)?\\s+(price|quote|value)\\b"),
            Regex("^(search|look up|find|research)\\b.*\\b(latest|newest|current|available)\\b.*\\b(model|nvidia|ai model|release|version)\\b|^(search|look up|find|research)\\b.*\\b(nvidia|nemotron|model catalog)\\b"),
            Regex("^(save|remember|keep|store)\\b.*\\b(this preference|my preference|preference|prefer|i like|i want you to)\\b|^(from now on|going forward)\\b.*\\b(always|prefer|remember)\\b"),
            Regex("^(remember this|save this|save to memory|remember that|recall my|search my memories|show my memories|update my memory|forget this|forget my|delete memory)\\b"),
            Regex("^(list my tasks|show my tasks|create a task|add a task|update my task|complete my task|delete my task|list my skills|show my skills|run skill)\\b"),
            Regex("^(send (a )?(message|sms|telegram)|draft (a )?(text|sms|message)|call [a-z0-9+]|set (an )?alarm|set (a )?timer|turn (on|off) (the )?(flashlight|torch)|change (my )?volume|check my battery|read my screen|take a screenshot|record my screen)\\b"),
            Regex("^(check|show|open) (my )?(connected services|integrations|browser|task list|skills|memory manager|saved tasks)\\b")
        )
        return patterns.any { it.containsMatchIn(text) } ||
            FastIntents.parse(text) != null ||
            LiveSports.isScoreRequest(text) || WeatherService.isWeatherRequest(text) ||
            LiveNews.isNewsRequest(text) || LiveWebSearch.isLiveInfoRequest(text) || LiveWebSearch.isSearchRequest(text)
    }

    private fun submit(
        text: String,
        conversationOnly: Boolean = false,
        autoRoute: Boolean = false
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        stopOfflineWakeDetector()
        if (running) {
            main.post {
                mainChatListener?.onMessage("ai", "I'm already working on another task. Let that finish, then send this request again.")
                mainChatListener?.onStatus("Another task is still running", currentMode())
            }
            return
        }
        if (Regex("^(goodbye|bye|bye bye|end call|end the call|hang up|stop listening|that's all|that is all)[.!]?$").matches(t.lowercase())) {
            handsActive = false
            appendTranscript("me", t)
            appendTranscript("ai", "Talk soon.")
            voice.stopListening()
            listening = false
            if (Prefs.voice(this)) voice.speak("Talk soon.")
            endHands()
            return
        }
        // In a user-started live call, recognised speech is always a conversation turn.
        // Do not route everyday words through the command detector; the user can switch
        // to the separate chat command workflow when they want phone actions.
        if (handsActive) {
            wakeGen++
            appendTranscript("me", t)
            mainChatListener?.onMessage("me", t)
            voice.stopSpeaking()
            voice.stopListening()
            listening = false
            hideBubble()
            running = true
            phase = "thinking"
            refreshMode()
            // Natural speech stays conversational. Phone actions in a live call need
            // the explicit wake phrase ("Orion, open ...") so a passing word never triggers
            // an accidental tap, message, or other action.
            val explicitCommand = afterWake(t)?.takeIf { it.isNotBlank() }
            worker.execute {
                try {
                    if (explicitCommand != null) agent.run(explicitCommand)
                    else agent.runLiveConversation(t)
                } catch (e: Exception) {
                    say("I couldn't reach the AI just now. Please try again.")
                }
                main.post { finishRun() }
            }
            return
        }

        val savedRoutineCommand = RoutineReplayPolicy.parseRequest(t)?.let { request ->
            RoutineReplayPolicy.exactName(MacroStore.names(this), request.name) != null ||
                RoutineReplayPolicy.isExplicitRoutineCommand(t)
        } == true
        val routeToAgent = autoRoute && shouldAutoRouteToAgent(t)
        if (routeToAgent && conversationOnly) notifyModeSwitch(true)
        if (!savedRoutineCommand && ((conversationOnly && !routeToAgent) || (isConversationStarter(t) && !LiveWebSearch.isSearchRequest(t) && !LiveNews.isNewsRequest(t) && !routeToAgent))) {
            wakeGen++
            appendTranscript("me", t)
            mainChatListener?.onMessage("me", t)
            voice.stopSpeaking()
            voice.stopListening()
            listening = false
            hideBubble()
            running = true
            phase = "thinking"
            refreshMode()
            worker.execute {
                try {
                    agent.runConversation(t)
                } catch (e: Exception) {
                    say("I couldn't reach the AI just now. " + (e.message ?: "Please try again."))
                }
                main.post { finishRun() }
            }
            return
        }

        wakeGen++
        appendTranscript("me", t)
        // Show the user's message immediately, like a real chat app, rather than
        // waiting for the model response to make the conversation feel alive.
        if (panel != null) {
            addLine("me", t)
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
        voice.stopSpeaking()
        voice.stopListening()
        listening = false

        // Keep ordinary conversation inside the chat surface. Hide it only when Orion
        // needs to see or operate another app, otherwise the user sees a disappearing strip
        // instead of a continuous chatbot conversation.
        val needsPhoneControl = AgentHooks.isPhoneActionRequest(t) || savedRoutineCommand
        if (needsPhoneControl) closePanel(false)
        if (mainChatActive) mainChatListener?.onMessage("me", t)
        hideBubble()
        running = true
        phase = "thinking"
        if (panel != null) {
            panelStatus?.text = "Thinking..."
            hideStrip()
        } else if (!mainChatActive) {
            showStrip("Thinking...")
        }
        refreshMode()
        if (panel != null) showThinkingIndicator()
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
        hideThinkingIndicator()
        running = false
        refreshMode()
        val last = transcript.lastOrNull { it.first == "ai" }?.second ?: "Done."
        if (panel != null) {
            panelStatus?.text = "Ready"
            hideStrip()
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        } else if (!mainChatActive) {
            showStrip(last)
        }
        if (handsActive) {
            voice.whenIdle { if (handsActive && !running) listenNow() }
        } else {
            handsActive = false
            if (!mainChatActive) {
                showBubble()
                startWake()
                main.postDelayed({
                    if (!running && !listening) hideStrip()
                }, 7000)
            }
        }
    }

    /** Starts the continuous, user-initiated live conversation used by the full-screen voice UI. */
    fun startLiveConversation() {
        main.post { startLive() }
    }

    /** Live call: Orion listens, answers, listens again, until you say goodbye or stay quiet. */
    private fun startLive() {
        if (running) {
            showStatusText("Orion is already working. Wait or stop the current task before starting live voice.")
            return
        }
        if (listening) {
            handsActive = true
            emptyListens = 0
            showStatusText("Live voice is active. Go ahead when ready.")
            return
        }
        handsActive = true
        emptyListens = 0
        closePanel(false)
        // A spoken greeting adds a full TTS cycle before the microphone opens and is a
        // common cause of users thinking the live call has timed out. Start listening now.
        voice.stopSpeaking()
        listenNow()
    }

    private fun listenNow() {
        if (running) return
        stopOfflineWakeDetector()
        wakeGen++
        listening = true
        hideBubble()
        if (panel == null && !mainChatActive) showStrip("Listening...")
        voice.stopSpeaking()
        refreshMode()
        var errored = false
        voice.listen({ text ->
            listening = false
            refreshMode()
            if (text.isNullOrBlank()) {
                emptyListens++
                // A silence timeout is not the end of a live call. Re-arm recognition until
                // the user ends the call or the speech service reports a real error.
                if (handsActive && !errored) {
                    if (emptyListens % 3 == 0) {
                        showStatusText("Still listening — speak whenever you're ready.")
                    }
                    main.postDelayed({ if (handsActive && !running) listenNow() }, 300)
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
            appendTranscript("note", err)
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

    /** True until the worker has actually left its current run. */
    fun isTaskRunning(): Boolean = running

    /** Public entry point for the full-screen chat Stop control. */
    fun stopCurrentTask() {
        stopAll()
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
            panelStatus?.text = "Stopping..."
        } else {
            hideStrip()
            showBubble()
            startWake()
        }
        refreshMode()
    }

    // ---------------------------------------------------------------- wake word "Orion"

    private fun wakeAllowed(): Boolean =
        Prefs.wake(this) && !running && !listening && !mainChatActive && panel == null && confirmView == null &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWake() {
        if (!wakeAllowed()) return
        if (voice.isSpeaking()) { main.postDelayed({ startWake() }, 800); return }
        if (WakeWordPolicy.useOfflineModel(WakeWordModelInstaller.isInstalled(this), offlineWakeFailedForSession)) {
            val detector = offlineWakeDetector ?: OfflineWakeWordDetector(
                this,
                onWake = { main.post {
                    if (wakeAllowed()) {
                        wakeGen++
                        stopOfflineWakeDetector()
                        showStatusText("Orion heard the wake word. Speak your command now.")
                        listenNow()
                    }
                } },
                onError = { error -> main.post {
                    if (wakeAllowed()) {
                        offlineWakeFailedForSession = true
                        stopOfflineWakeDetector()
                        appendTranscript("note", "Offline wake word failed; switching to Android recognizer fallback: $error")
                        showStatusText("Offline wake detector unavailable. Using Android recognizer fallback.")
                        wakeGen++
                        main.postDelayed({ if (wakeAllowed()) startWake() }, 300)
                    }
                } },
                onDiagnostic = { diagnostic -> main.post {
                    if (wakeAllowed() && Prefs.wakeTestMode(this)) showStatusText(diagnostic)
                } }
            ).also { offlineWakeDetector = it }
            if (detector.start()) return
        }
        val my = ++wakeGen
        main.postDelayed({ wakeTick(my) }, 900)
    }

    private fun stopOfflineWakeDetector() {
        try { offlineWakeDetector?.stop() } catch (_: Exception) { }
        offlineWakeDetector = null
    }

    /** Apply the wake-word preference immediately without requiring an Accessibility restart. */
    fun updateWakeWord(enabled: Boolean) {
        main.post {
            wakeGen++
            if (enabled) offlineWakeFailedForSession = false
            if (!enabled) {
                stopOfflineWakeDetector()
                if (!listening) voice.stopListening()
            } else if (!mainChatActive && !running && !listening) {
                startWake()
            }
        }
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
                if (!wakeErr) wakeErrorStreak = 0
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
                    // Give the Android recognizer a little breathing room between sessions.
                    main.postDelayed({ wakeTick(my) }, 900)
                }
            }
        }, { _ -> }, { err ->
            wakeErr = true
            wakeErrorStreak = (wakeErrorStreak + 1).coerceAtMost(6)
            appendTranscript("note", "Wake word: $err")
            // 5s, 10s, 20s, then cap at 30s while the speech service is unhealthy.
            val retryMs = (5000L shl (wakeErrorStreak - 1)).coerceAtMost(30_000L)
            main.postDelayed({ wakeTick(my) }, retryMs)
        })
    }

    /** Returns what was said after "Orion", or null if the wake word was not said. */
    private fun afterWake(text: String): String? {
        val m = WAKE.find(text) ?: return null
        return text.substring(m.range.last + 1).trim().trimStart(',', '.', '!', ':', ' ')
    }

    // ---------------------------------------------------------------- AgentUi

    override fun say(text: String) {
        appendTranscript("ai", text)
        main.post {
            hideThinkingIndicator()
            if (panel != null) {
                addLine("ai", text)
                panelStatus?.text = if (running) "Working..." else "Ready"
                hideStrip()
            } else if ((running || listening) && !mainChatActive) {
                showStrip(text)
            }
        }
        main.post { mainChatListener?.onMessage("ai", text) }
        if (Prefs.voice(this)) voice.speak(text)
    }

    override fun status(text: String) {
        main.post {
            if (panel != null) {
                panelStatus?.text = text
            } else if (running) {
                // While the user is looking at Orion's own chat, its header already shows status;
                // a floating strip would just cover the title and Stop controls.
                val inOwnChat = mainChatActive && try { currentPackageName() == packageName } catch (_: Exception) { false }
                if (inOwnChat) mainChatListener?.onStatus(text, OrbView.Mode.THINKING) else showStrip(text)
            }
        }
    }

    override fun stream(text: String) {
        if (text.isEmpty()) return
        main.post { mainChatListener?.onAssistantDelta(text) }
    }

    override fun note(text: String) {
        appendTranscript("note", text)
        main.post {
            mainChatListener?.onMessage("note", text)
            if (panel != null) {
                addLine("note", text)
            } else if (running) {
                showStrip(text)
            }
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
        recordChatEvent("approval", if (answer.get()) "approved" else "denied", "Approval request reviewed by user")
        return answer.get()
    }

    override fun askUser(question: String, options: List<String>, access: String): String {
        val latch = CountDownLatch(1)
        val answer = java.util.concurrent.atomic.AtomicReference("")
        main.post {
            showUserChoices(question, options, access) { choice ->
                answer.set(choice)
                latch.countDown()
            }
        }
        if (Prefs.voice(this)) voice.speak(question)
        latch.await()
        return answer.get()
    }

    // ---------------------------------------------------------------- tools: apps

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

    /** Current foreground package exposed only for the local safety gate. */
    fun currentPackageName(): String = try { rootInActiveWindow?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }

    fun currentAppLabel(): String {
        val pkg = currentPackageName()
        if (pkg.isBlank()) return ""
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
    }

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
            // Only accept a label that contains the request ("youtube" may match "YouTube Kids"); never the reverse,
            // or "YouTube Music" would silently open the plain YouTube app when Music is not installed.
            if (best == null && lbl.isNotEmpty() && lbl.contains(q)) {
                best = ri
                bestLabel = ri.loadLabel(pm).toString()
            }
        }
        if (best == null) {
            val names = list.map { it.loadLabel(pm).toString() }.sorted().take(40)
            return "error: app not found. Installed apps include: " + names.joinToString(", ")
        }
        if (SafetyGate.isSensitiveApp(best.activityInfo.packageName, bestLabel)) {
            return "error: Orion's safety gate blocks opening or automating banking and payment apps. Open and operate that app manually."
        }
        val launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: return "error: cannot launch $bestLabel"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        // Return as soon as the target app is in front instead of always waiting 1.8 s.
        val target = best.activityInfo.packageName
        var waited = 0
        while (waited < 1800) {
            Thread.sleep(150); waited += 150
            if (try { currentPackageName() == target } catch (_: Exception) { false }) { Thread.sleep(250); break }
        }
        return "opened $bestLabel"
    }

    fun openUrl(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return "error: URL is empty"
        val candidate = if (raw.startsWith("https://", true) || raw.startsWith("http://", true)) raw else "https://$raw"
        val safe = ChatBrowserUrlPolicy.normalize(candidate)
            ?: return "error: provide a valid public HTTP or HTTPS website URL"
        val uri = try { Uri.parse(safe) } catch (_: Exception) { null }
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "error: provide a valid http or https website URL"
        }

        // Prefer the chat-owned browser surface whenever chat is attached. This keeps web-search
        // and open-URL shortcuts in the conversation instead of unexpectedly switching activities.
        if (mainChatListener != null) {
            main.post {
                val listener = mainChatListener
                if (listener != null) {
                    listener.onOpenBrowser(safe)
                } else {
                    try {
                        startActivity(Intent(this, BrowserActivity::class.java)
                            .putExtra(BrowserActivity.EXTRA_URL, safe)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
            }
            return "opened in the in-chat browser popup: $safe"
        }

        val i = Intent(this, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_URL, safe)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            BrowserAutomation.awaitWebView(3000L)
            "opened in Orion's in-app browser: $safe"
        } catch (e: Exception) {
            "error: could not open in-app browser (" + (e.message ?: "launch failed") + ")"
        }
    }

    // ---------------------------------------------------------------- tools: reading the screen

    fun readScreen(): String {
        // Android can briefly return null during app/window transitions even when the service
        // remains connected. Retry a few short times before reporting a genuine missing root.
        var root = rootInActiveWindow
        var rootAttempts = 0
        while (root == null && rootAttempts < 3) {
            try {
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            root = rootInActiveWindow
            rootAttempts++
        }
        if (root == null) {
            val serviceReady = runCatching { serviceInfo != null }.getOrDefault(false)
            return if (serviceReady) {
                "error: Android has not exposed an accessibility root for the current window after a short retry. The service is connected; the app may still be transitioning, a system surface may be active, or the window may not expose accessibility nodes. Wait for the target app to settle and retry once; do not infer that accessibility permission is missing."
            } else {
                "error: Orion accessibility service is not connected. Enable Orion in Android Accessibility settings, return to the target app, then retry screen reading."
            }
        }
        nodes.clear()
        ocrElements.clear()
        visualElements.clear()
        val size = screenSize()
        val sb = StringBuilder()
        sb.append("screen ${size.first}x${size.second}\n")
        walk(root, sb)

        // OCR is local-only. Do not OCR sensitive app screens or any editable/password field.
        val visibleLabels = nodes.mapNotNull { n ->
            if (n.isPassword) "(password field)" else if (n.isEditable) n.hintText?.toString()?.lowercase()
            else (n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString())?.lowercase()
        }.joinToString(" ")
        val packageId = root.packageName?.toString()?.lowercase().orEmpty()
        val sensitiveScreen = Regex("bank|wallet|payment|pay\\b|authenticator|password|credential|otp|one.?time.?code")
            .containsMatchIn(packageId + " " + visibleLabels)
        if (!sensitiveScreen) {
            try {
                val shot = screenshotBase64()
                if (shot != null) {
                    val bytes = Base64.decode(shot.b64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        try {
                            val excluded = nodes.filter { it.isPassword || it.isEditable }.mapNotNull { node ->
                                val r = Rect()
                                node.getBoundsInScreen(r)
                                if (r.width() <= 0 || r.height() <= 0) null else Rect(
                                    (r.left / shot.scale).toInt(), (r.top / shot.scale).toInt(),
                                    (r.right / shot.scale).toInt(), (r.bottom / shot.scale).toInt()
                                )
                            }
                            val found = ScreenPerception.recognize(bitmap, excluded)
                            val existing = nodes.mapNotNull { n ->
                                (n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString())
                                    ?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")
                            }.toHashSet()
                            for (item in found) {
                                val normalized = item.text.trim().lowercase().replace(Regex("\\s+"), " ")
                                if (normalized in existing || ocrElements.size >= 20) continue
                                val scaled = Rect(
                                    (item.bounds.left * shot.scale).toInt(),
                                    (item.bounds.top * shot.scale).toInt(),
                                    (item.bounds.right * shot.scale).toInt(),
                                    (item.bounds.bottom * shot.scale).toInt()
                                )
                                if (scaled.width() <= 0 || scaled.height() <= 0) continue
                                ocrElements.add(ScreenPerception.OcrItem(item.text, scaled))
                                val id = nodes.size + ocrElements.lastIndex
                                sb.append("[$id] OCR \"${item.text.take(70)}\"\n")
                                existing.add(normalized)
                            }
                            // Accessibility + OCR always run first. Invoke the detector only for sparse
                            // screens (common in games, WebViews and custom-drawn interfaces).
                            if (nodes.size + ocrElements.size < 8) {
                            val occupied = ArrayList<Rect>()
                            nodes.forEach { node ->
                                val r = Rect()
                                node.getBoundsInScreen(r)
                                // Ignore container nodes such as a full-screen WebView; they are not individual targets.
                                if (r.width() > 0 && r.height() > 0 &&
                                    r.width().toLong() * r.height() < bitmap.width.toLong() * bitmap.height * 0.40) {
                                    occupied.add(Rect(
                                        (r.left / shot.scale).toInt(), (r.top / shot.scale).toInt(),
                                        (r.right / shot.scale).toInt(), (r.bottom / shot.scale).toInt()
                                    ))
                                }
                            }
                            ocrElements.forEach { item -> occupied.add(Rect(
                                (item.bounds.left / shot.scale).toInt(), (item.bounds.top / shot.scale).toInt(),
                                (item.bounds.right / shot.scale).toInt(), (item.bounds.bottom / shot.scale).toInt()
                            )) }
                            val detectorStartedAt = SystemClock.elapsedRealtime()
                            val detected = UiYoloTfliteDetector.detect(this, bitmap, excluded + occupied)
                            val regions = detected?.map {
                                VisualElementDetector.Region(it.bounds, it.confidence, it.label)
                            } ?: VisualElementDetector.detect(bitmap, excluded + occupied)
                            val detectorMs = SystemClock.elapsedRealtime() - detectorStartedAt
                            sb.append("(visual detector: ${if (detected != null) "YOLO TFLite" else "heuristic fallback"}, ${detectorMs} ms)\\n")
                            for (region in regions) {
                                if (visualElements.size >= 10) break
                                val scaled = Rect(
                                    (region.bounds.left * shot.scale).toInt(), (region.bounds.top * shot.scale).toInt(),
                                    (region.bounds.right * shot.scale).toInt(), (region.bounds.bottom * shot.scale).toInt()
                                )
                                if (scaled.width() <= 0 || scaled.height() <= 0) continue
                                visualElements.add(VisualElementDetector.Region(scaled, region.confidence, region.label))
                                val id = nodes.size + ocrElements.size + visualElements.lastIndex
                                sb.append("[$id] visual target (${region.label}, ${(region.confidence * 100).toInt()}% confidence)\\n")
                            }
                            }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            } catch (_: Exception) {
                sb.append("(on-device OCR unavailable for this screen; accessibility results are still available)\n")
            }
        } else {
            sb.append("(OCR withheld on a potentially sensitive screen)\n")
        }
        if (nodes.size + ocrElements.size + visualElements.size < 3) {
            sb.append("(very few readable elements: this may be a game or custom-drawn screen)\n")
        }
        return sb.toString().take(2800)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= 45) return
        // Never put the current contents of an editable field into the screen map.
        val text = if (n.isEditable) "" else n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString() ?: ""
        val lbl = when {
            n.isPassword -> "(password field)"
            n.isEditable -> n.hintText?.toString()?.takeIf { it.isNotBlank() } ?: "(text field)"
            else -> text.trim()
        }
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
                sb.append("[$id] $kind \"${lbl.take(40)}\"\n")
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            if (c != null) walk(c, sb)
        }
    }

    /** Label and centre of an element from the latest read_screen (used to save routines). */
    fun nodeInfo(id: Int): Triple<String, Int, Int>? {
        if (id >= nodes.size + ocrElements.size) {
            val item = visualElements.getOrNull(id - nodes.size - ocrElements.size) ?: return null
            return Triple(item.label, item.bounds.centerX(), item.bounds.centerY())
        }
        if (id >= nodes.size) {
            val item = ocrElements.getOrNull(id - nodes.size) ?: return null
            return Triple(item.text.take(40), item.bounds.centerX(), item.bounds.centerY())
        }
        val n = nodes.getOrNull(id) ?: return null
        if (n.isPassword) return null
        val r = Rect()
        n.getBoundsInScreen(r)
        val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().take(40)
        return Triple(label, r.centerX(), r.centerY())
    }

    fun clickId(id: Int): String {
        if (id >= nodes.size + ocrElements.size) {
            val item = visualElements.getOrNull(id - nodes.size - ocrElements.size)
                ?: return "error: no element $id. Call read_screen first."
            return tap(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
        }
        if (id >= nodes.size) {
            val item = ocrElements.getOrNull(id - nodes.size)
                ?: return "error: no element $id. Call read_screen first."
            return tap(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
        }
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
        if (text.isEmpty()) return "error: refusing to type empty text"
        if (text.length > 4000) return "error: text exceeds the 4000 character limit"
        if (SafetyGate.containsSensitiveCredentialText(text)) {
            return "error: Orion's safety gate blocks typing card, CVV/CVC, password, passcode, PIN, OTP, security-code, or card-number text. Enter sensitive details yourself."
        }
        val root = rootInActiveWindow ?: return "error: cannot inspect the current screen"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "error: no editable text field found. Click the intended field first."
        if (field.isPassword) return "error: refusing to type into a password field. Ask the user to enter credentials themselves."
        val targetPackage = field.packageName?.toString()
        // Flutter and React Native fields may reject ACTION_SET_TEXT. Focus the target, then
        // send the agent's explicit payload through Orion's InputConnection instead.
        try {
            if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) { }
        return OrionKeyboardService.typeIntoFocusedField(this, text, targetPackage)
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

    /** Replays a recorded multi-point gesture without an AI call. */
    fun performRecordedPath(points: JSONArray, durationMs: Long): String {
        if (points.length() < 2) return "error: recorded gesture has too few points"
        val path = Path()
        for (i in 0 until points.length()) {
            val p = points.optJSONObject(i) ?: continue
            val x = p.optDouble("x", Double.NaN).toFloat()
            val y = p.optDouble("y", Double.NaN).toFloat()
            if (!x.isFinite() || !y.isFinite()) continue
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return gesture(path, durationMs.coerceIn(80L, 8000L))
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

    // ---------------------------------------------------------------- fast, no-AI: teach mode + screen checks

    private val cbExec = Executors.newSingleThreadExecutor()
    private val sigExec = Executors.newFixedThreadPool(2)
    private val teachExec = Executors.newSingleThreadExecutor()

    /** Full-size screenshot with no AI and no resizing. Null if the screen is protected. */
    private fun grabBitmap(): Bitmap? {
        val holder = arrayOfNulls<Bitmap>(1)
        val latch = CountDownLatch(1)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                cbExec,
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
        } catch (e: Exception) {
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return holder[0]
    }

    /** A tiny "fingerprint" of the area around a point: 6x6 average colours (108 numbers). */
    private fun signature(bmp: Bitmap, cx: Int, cy: Int): IntArray {
        val r = 60
        val x0 = (cx - r).coerceIn(0, bmp.width - 2)
        val y0 = (cy - r).coerceIn(0, bmp.height - 2)
        val x1 = (cx + r).coerceIn(x0 + 1, bmp.width - 1)
        val y1 = (cy + r).coerceIn(y0 + 1, bmp.height - 1)
        val out = IntArray(6 * 6 * 3)
        for (gy in 0 until 6) {
            for (gx in 0 until 6) {
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (j in 0 until 4) {
                    for (i in 0 until 4) {
                        val px = x0 + ((gx * 4 + i) * (x1 - x0)) / 24
                        val py = y0 + ((gy * 4 + j) * (y1 - y0)) / 24
                        val c = bmp.getPixel(px.coerceIn(0, bmp.width - 1), py.coerceIn(0, bmp.height - 1))
                        sr += (c shr 16) and 255
                        sg += (c shr 8) and 255
                        sb += c and 255
                        n++
                    }
                }
                val k = (gy * 6 + gx) * 3
                out[k] = sr / n
                out[k + 1] = sg / n
                out[k + 2] = sb / n
            }
        }
        return out
    }

    /** Average colour difference (0 = identical, 255 = opposite). */
    private fun sigDistance(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 255.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /** Waits (no AI) until the screen around (x,y) looks like the taught fingerprint. Returns ok, timeout, stopped or unavailable. */
    fun waitForSig(x: Int, y: Int, sig: IntArray, timeoutMs: Long, stopped: () -> Boolean): String {
        val end = System.currentTimeMillis() + timeoutMs
        var fails = 0
        while (System.currentTimeMillis() < end) {
            if (stopped()) return "stopped"
            val bmp = grabBitmap()
            if (bmp == null) {
                fails++
                if (fails >= 3) return "unavailable"
                Thread.sleep(300)
                continue
            }
            val d = sigDistance(signature(bmp, x, y), sig)
            bmp.recycle()
            if (d <= 32.0) return "ok"
            Thread.sleep(120)
        }
        return "timeout"
    }

    private var teachView: FrameLayout? = null
    private var teachLp: WindowManager.LayoutParams? = null
    private var teachName = ""
    private var teachCounter: TextView? = null
    private val teachSteps = JSONArray()
    private var teachLastEnd = 0L
    private var tDownX = 0f
    private var tDownY = 0f
    private var tDownUp = 0L
    private var tDownWall = 0L
    private var tDownPackage = ""
    private var tSig: java.util.concurrent.Future<IntArray?>? = null
    private val teachPathPoints = ArrayList<Pair<Float, Float>>()

    fun isTeaching(): Boolean = teachView != null

    /** Starts recording the user's own taps and swipes. */
    fun startTeach(name: String): String {
        if (teachView != null) return "error: already teaching"
        teachName = name
        synchronized(teachSteps) {
            while (teachSteps.length() > 0) teachSteps.remove(0)
        }
        teachLastEnd = System.currentTimeMillis()
        main.post {
            closePanel(true)
            buildTeachOverlay()
        }
        return "ok"
    }

    private fun buildTeachOverlay() {
        val root = FrameLayout(this)
        // Keep the overlay transparent: screen fingerprints must describe the target app,
        // not a tinted recording layer that disappears during replay.
        root.setBackgroundColor(0x00000000)
        root.setOnTouchListener { _, e ->
            onTeachTouch(e)
            true
        }
        val done = TextView(this)
        teachCounter = done
        done.text = "● REC  ·  0 STEPS  ·  TAP TO SAVE"
        done.setTextColor(Theme.TEXT)
        done.textSize = 14f
        done.typeface = Typeface.DEFAULT_BOLD
        done.setPadding(dp(18), dp(10), dp(18), dp(10))
        done.background = rounded(0xE6C0243F.toInt(), dp(22).toFloat())
        done.setOnClickListener { stopTeach() }
        val dlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        dlp.topMargin = dp(40)
        root.addView(done, dlp)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            OVERLAY,
            NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, lp)
            teachView = root
            teachLp = lp
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start teach mode", Toast.LENGTH_LONG).show()
        }
    }

    private fun onTeachTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tDownX = e.rawX
                tDownY = e.rawY
                tDownUp = SystemClock.uptimeMillis()
                tDownWall = System.currentTimeMillis()
                tDownPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
                teachPathPoints.clear()
                teachPathPoints.add(Pair(e.rawX, e.rawY))
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                tSig = sigExec.submit(java.util.concurrent.Callable<IntArray?> {
                    val b = grabBitmap()
                    if (b == null) null else signature(b, x, y).also { b.recycle() }
                })
            }
            MotionEvent.ACTION_MOVE -> {
                val last = teachPathPoints.lastOrNull()
                if (last == null || Math.hypot((e.rawX - last.first).toDouble(), (e.rawY - last.second).toDouble()) >= dp(5)) {
                    teachPathPoints.add(Pair(e.rawX, e.rawY))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val x1 = tDownX
                val y1 = tDownY
                val lastPoint = teachPathPoints.lastOrNull() ?: Pair(e.rawX, e.rawY)
                val x2 = if (e.actionMasked == MotionEvent.ACTION_CANCEL) lastPoint.first else e.rawX
                val y2 = if (e.actionMasked == MotionEvent.ACTION_CANCEL) lastPoint.second else e.rawY
                val dur = SystemClock.uptimeMillis() - tDownUp
                val wall = tDownWall
                val sigF = tSig
                val points = ArrayList(teachPathPoints)
                if (points.isEmpty() || points.last() != Pair(x2, y2)) points.add(Pair(x2, y2))
                // Show a clear busy state while this gesture is forwarded and saved. The
                // overlay must briefly stop intercepting input while Android injects the same
                // gesture into the target app; make that short pause visible instead of silently
                // losing a rapid next touch.
                main.post { teachCounter?.text = "… SAVING STEP · WAIT FOR REC" }
                setTeachTouchable(false)
                teachExec.execute { teachReplay(x1, y1, x2, y2, dur, wall, sigF, points) }
            }
        }
    }

    private fun setTeachTouchable(touchable: Boolean) {
        onMainWait {
            val v = teachView
            val lp = teachLp
            if (v != null && lp != null) {
                lp.flags = if (touchable) {
                    lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                try {
                    wm.updateViewLayout(v, lp)
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Passes the user's touch through to the game, and writes it down as a step. */
    private fun teachReplay(
        x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, wall: Long,
        sigF: java.util.concurrent.Future<IntArray?>?,
        points: List<Pair<Float, Float>>
    ) {
        val sig = try {
            sigF?.get(400, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
        val dist = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val size = screenSize()
        val step = JSONObject().put("screenW", size.first).put("screenH", size.second).put("targetPackage", tDownPackage)
        val path = Path()
        path.moveTo(x1, y1)
        val ms: Long
        if (dist < dp(14)) {
            if (dur >= 600) {
                ms = 900L
                step.put("tool", "long_press").put("args", JSONObject().put("x", x1.toInt()).put("y", y1.toInt()))
            } else {
                ms = 60L
                step.put("tool", "tap").put("args", JSONObject().put("x", x1.toInt()).put("y", y1.toInt()))
            }
        } else {
            points.forEach { point -> path.lineTo(point.first, point.second) }
            ms = dur.coerceIn(80L, 8000L)
            val pointArray = JSONArray()
            points.forEach { point ->
                pointArray.put(JSONObject().put("x", point.first.toInt()).put("y", point.second.toInt()))
            }
            step.put("tool", "swipe_path").put(
                "args", JSONObject().put("points", pointArray).put("ms", ms)
            )
        }
        val r = try {
            gesture(path, ms)
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}"
        } finally {
            // Always unlock recording, even if the synthetic touch fails.
            setTeachTouchable(true)
        }
        if (r == "ok") {
            step.put("gap", (wall - teachLastEnd).coerceIn(0L, 120000L))
            if (sig != null) {
                val arr = JSONArray()
                for (v in sig) arr.put(v)
                step.put("sig", arr).put("sx", x1.toInt()).put("sy", y1.toInt())
                // Save a distinct post-gesture fingerprint when the gesture visibly changed this region.
                // It lets resume distinguish an unexecuted touch from one that probably already happened.
                val afterBitmap = grabBitmap()
                if (afterBitmap != null) {
                    try {
                        // Compare pre/post captures at the same anchor. Comparing a swipe's
                        // start point to its end point would confuse spatial differences with a state change.
                        val after = signature(afterBitmap, x1.toInt(), y1.toInt())
                        if (sigDistance(sig, after) >= 8.0) {
                            val afterArr = JSONArray()
                            for (v in after) afterArr.put(v)
                            step.put("afterSig", afterArr).put("afterSx", x1.toInt()).put("afterSy", y1.toInt())
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (!afterBitmap.isRecycled) afterBitmap.recycle()
                    }
                }
            }
            val count = synchronized(teachSteps) { teachSteps.put(step); teachSteps.length() }
            main.post { teachCounter?.text = "● REC  ·  $count STEPS  ·  TAP TO SAVE" }
        } else {
            main.post {
                Toast.makeText(this, "A recorded touch could not be replayed, so that step was not saved. Please repeat it.", Toast.LENGTH_LONG).show()
            }
        }
        main.post {
            val count = synchronized(teachSteps) { teachSteps.length() }
            teachCounter?.text = "● REC  ·  $count STEPS  ·  TAP TO SAVE"
        }
        teachLastEnd = System.currentTimeMillis()
    }

    private fun stopTeach() {
        val v = teachView ?: return
        teachView = null
        teachLp = null
        teachCounter = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        teachExec.execute {
            val copy = JSONArray()
            synchronized(teachSteps) {
                for (i in 0 until teachSteps.length()) copy.put(teachSteps.get(i))
            }
            val ok = copy.length() > 0 && MacroStore.save(this, teachName, copy)
            main.post {
                val msg = if (ok) "Saved \"$teachName\" (${copy.length()} steps). Say: run $teachName" else "Nothing was recorded."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                showPanel()
            }
        }
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
                    val width = OrionPerformancePolicy.screenshotWidth(bmp.width)
                    val scale = bmp.width / width.toFloat()
                    val height = (bmp.height / scale).toInt()
                    val small = Bitmap.createScaledBitmap(bmp, width, height, true)
                    try {
                        val out = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        return Shot(b64, scale, width, height)
                    } finally {
                        if (small !== bmp && !small.isRecycled) small.recycle()
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
                Thread.sleep(1100)
            }
            return null
        } finally {
            onMainWait { setOverlaysVisible(true) }
        }
    }
}
