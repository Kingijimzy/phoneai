package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.method.LinkMovementMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Last turns per agent, kept on the phone. */
internal object AgentChatStore {
    private fun file(c: Context, id: String) = File(c.filesDir, "orion_agent_chat_$id.json")

    fun load(c: Context, id: String): MutableList<AgentRunner.Turn> = try {
        val a = JSONArray(file(c, id).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); AgentRunner.Turn(o.getString("role"), o.getString("text")) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun save(c: Context, id: String, turns: List<AgentRunner.Turn>) {
        try {
            val a = JSONArray()
            turns.takeLast(40).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
            file(c, id).writeText(a.toString())
        } catch (_: Exception) { }
    }

    fun clear(c: Context, id: String) { file(c, id).delete() }
}

class AgentChatActivity : Activity() {
    companion object {
        const val EXTRA_AGENT = "agent_id"

        /** The trading workspace: the coach chat is the front door of the desk. */
        private const val LOOKS = 5 // orb, fox, robot (drawn), dot-matrix orb, hologram head
        fun workspaceIntent(c: Context): Intent = Intent(c, AgentChatActivity::class.java).putExtra(EXTRA_AGENT, "coach")
        private const val REQUEST_IMAGE = 7422
    }

    private lateinit var agent: AgentProfile
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var attachLabel: TextView
    private val turns = mutableListOf<AgentRunner.Turn>()
    private var pendingImage: String? = null
    @Volatile private var busy = false
    private var deskStatus: TextView? = null
    private var pnlText: TextView? = null
    private var moodText: TextView? = null
    private var mascot: CompanionView? = null
    private var orb: OrbView? = null
    private var holo: HologramView? = null
    private var look = 0
    private var liveState = CompanionView.Activity.IDLE
    private var lastStrip = "ORION"
    private var lastLine = ""
    private var listeningNow = false
    private var speakingNow = false
    private var wakeOn = false
    private var wake: OfflineWakeWordDetector? = null
    private var voice: Voice? = null
    private var lastMood: CompanionMood.Mood? = null
    private var lastSpokeAt = 0L
    private var prevRealized: Double? = null
    private var greeted = false
    private var handsFree = false
    private var currentGroup = 0
    private var chipRow: LinearLayout? = null
    private var tabRow: LinearLayout? = null
    private var tabsHolder: android.widget.HorizontalScrollView? = null
    private var resumed = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (resumed) { refreshDesk(); startWake(); ticker.postDelayed(this, 20_000L) } } }

    private fun voiceOut(): Voice = voice ?: Voice(applicationContext).also { v ->
        v.onSpeaking = { on -> speakingNow = on; setLive(if (on) CompanionView.Activity.SPEAKING else CompanionView.Activity.IDLE) }
        v.onLevel = { l -> orb?.level = l; holo?.mic(l) }
        voice = v
    }

    /** One place that moves every visual (drawn character, dot-matrix orb, hologram) to the same state. */
    private fun setLive(a: CompanionView.Activity) {
        liveState = a
        mascot?.activity = a
        orb?.mode = when (a) {
            CompanionView.Activity.IDLE -> OrbView.Mode.IDLE
            CompanionView.Activity.THINKING -> OrbView.Mode.THINKING
            CompanionView.Activity.LISTENING -> OrbView.Mode.LISTENING
            CompanionView.Activity.SPEAKING -> OrbView.Mode.SPEAKING
        }
        pushHolo()
    }

    private fun pushHolo() {
        val h = holo ?: return
        if (h.visibility == android.view.View.VISIBLE) h.show(h.stateName(liveState), lastStrip, lastLine)
    }

    /** 0 to 2: drawn characters, 3: dot-matrix orb, 4: human-head hologram banner. Tap the character to cycle. */
    private fun applyLook(i: Int) {
        look = i.coerceIn(0, LOOKS - 1)
        Prefs.setCompanionChar(this, look)
        mascot?.visibility = if (look <= 2) android.view.View.VISIBLE else android.view.View.GONE
        if (look <= 2) mascot?.character = look
        orb?.visibility = if (look == 3) android.view.View.VISIBLE else android.view.View.GONE
        holo?.visibility = if (look == 4) android.view.View.VISIBLE else android.view.View.GONE
        mascot?.let { if (look <= 2) Motion.enter(it) }
        pushHolo()
    }

    private fun refreshDesk() {
        val strip = pnlText ?: return
        Thread {
            val t = try { TradingDesk.statusLine(this) } catch (_: Exception) { "Desk status unavailable" }
            val snap = try { TradingDesk.pnlSnapshot(this) } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                deskStatus?.text = t
                if (snap != null) {
                    strip.text = snap.strip; moodText?.text = snap.state.line; mascot?.setMood(snap.state.mood)
                    lastStrip = snap.strip; lastLine = snap.state.line; pushHolo()
                    val now = System.currentTimeMillis()
                    val reaction = CompanionLife.reactionTo(prevRealized, snap.realized, snap.equity, lastMood, snap.state.mood)
                    mascot?.react(reaction)
                    if (reaction != CompanionLife.Reaction.NONE) { holo?.pulse(0.9f); orb?.pulseMouth() }
                    prevRealized = snap.realized
                    if (Prefs.companionVoice(this) && CompanionMood.shouldSpeak(lastMood, snap.state.mood, lastSpokeAt, now)) {
                        lastSpokeAt = now; voiceOut().speak(snap.state.line)
                    }
                    lastMood = snap.state.mood
                    if (!greeted) {
                        greeted = true
                        val seen = Prefs.lastChatSeen(this)
                        val away = if (seen == 0L) 0L else (now - seen) / 60_000L
                        if (seen == 0L || away >= 30L) {
                            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                            val g = CompanionLife.greeting(hour, away, snap.positions, snap.state.mood, snap.newsMinutes)
                            addBubble(false, g)
                            if (Prefs.companionVoice(this)) voiceOut().speak(g)
                        }
                    }
                }
            }
        }.start()
    }

    private fun micTap() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 91); return
        }
        if (busy) return
        listeningNow = true
        input.hint = "Listening…"
        setLive(CompanionView.Activity.LISTENING)
        voiceOut().listen(
            onText = { t ->
                input.hint = "Ask, or upload a chart…"
                listeningNow = false
                setLive(CompanionView.Activity.IDLE)
                if (!t.isNullOrBlank()) { input.setText(t); send() }
                else if (handsFree) ticker.postDelayed({ if (handsFree && resumed && !busy) micTap() }, 700L)
            },
            onPartial = { input.setText(it); input.setSelection(input.text.length) },
            onProblem = { m ->
                android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
                listeningNow = false
                setLive(CompanionView.Activity.IDLE)
                if (handsFree) { handsFree = false; selectGroup(currentGroup) }
            }
        )
    }

    /** Hands-free: listen, send, speak the reply, then listen again until switched off. */
    private fun toggleHandsFree() {
        handsFree = !handsFree
        selectGroup(currentGroup)
        if (handsFree) {
            android.widget.Toast.makeText(this, "Hands-free on. Speak after the character starts listening. Tap Talk again to stop.", android.widget.Toast.LENGTH_LONG).show()
            micTap()
        } else {
            voice?.stopSpeaking(); setLive(CompanionView.Activity.IDLE)
        }
    }

    /** In-chat wake word: say "Orion" and the mic opens. Uses the offline model installed in Settings; audio stays on the device until it fires. */
    private fun toggleWake() {
        if (wakeOn) { wakeOn = false; stopWake(); selectGroup(currentGroup); return }
        when {
            Prefs.wake(this) -> android.widget.Toast.makeText(this, "The global wake word is on in Settings and the microphone can only serve one. Turn it off there first.", android.widget.Toast.LENGTH_LONG).show()
            !WakeWordModelInstaller.isInstalled(this) -> android.widget.Toast.makeText(this, "Install the offline wake-word model in Settings first. The wake word is \"Orion\".", android.widget.Toast.LENGTH_LONG).show()
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 92)
            else -> { wakeOn = true; startWake(); selectGroup(currentGroup)
                android.widget.Toast.makeText(this, "Say \"Orion\", then your request.", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun startWake() {
        if (!wakeOn || wake != null || listeningNow || speakingNow || busy || !resumed) return
        val d = OfflineWakeWordDetector(applicationContext, onWake = {
            runOnUiThread { stopWake(); if (wakeOn && resumed) micTap() }
        }, onError = { m -> runOnUiThread { wakeOn = false; stopWake(); android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show(); selectGroup(currentGroup) } })
        if (d.start()) wake = d else { wakeOn = false; selectGroup(currentGroup) }
    }

    private fun stopWake() { wake?.stop(); wake = null }

    private fun toggleVoice() {
        val on = !Prefs.companionVoice(this); Prefs.setCompanionVoice(this, on)
        if (!on) voice?.stopSpeaking()
        selectGroup(currentGroup)
    }

    private fun runAction(a: String) {
        when (a) {
            "quote" -> quoteDialog()
            "review" -> { input.setText("Review my trade: pair, side, entry, stop, exit and why I took it: "); input.setSelection(input.text.length) }
            "risk" -> riskDialog()
            "desk" -> startActivity(Intent(this, TradingActivity::class.java))
            "brain" -> startActivity(Intent(this, SoulEngineActivity::class.java))
            "voice" -> toggleVoice()
            "talk" -> toggleHandsFree()
            "mic" -> micTap()
            "wake" -> toggleWake()
            "caps" -> addBubble(false, "Here is what I can do for you:\n" + DeskCapabilities.items.joinToString("\n") { "- ${it.name}: ${it.does}" })
            "tour" -> addBubble(false, DeskTour.text)
            "health" -> {
                val wait = addBubble(false, "Checking your setup…")
                Thread {
                    val t = try { DeskHealth.text(TradingDesk.healthItems(this)) } catch (_: Exception) { "I could not run the setup check." }
                    runOnUiThread { if (!isDestroyed) { wait.text = Markdown.render(t) } }
                }.start()
            }
            "learned" -> notesDialog()
            "clear" -> { turns.clear(); AgentChatStore.clear(this, agent.id); messages.removeAllViews(); greet() }
        }
    }

    private fun chipLabel(c: ChatGroups.Chip): String = when (c.action) {
        "voice" -> "Voice: " + (if (Prefs.companionVoice(this)) "on" else "off")
        "talk" -> if (handsFree) "Talk: on" else "Talk"
        "wake" -> if (wakeOn) "Wake: on" else "Wake"
        else -> c.label
    }

    /** Shows one group of quick actions, with a short staggered entrance. */
    private fun selectGroup(i: Int) {
        currentGroup = i
        val row = chipRow ?: return
        val g = ChatGroups.coach[i]
        row.removeAllViews()
        g.chips.forEachIndexed { n, c ->
            val b = smallButton(chipLabel(c)) { if (c.prompt != null) quick(c.prompt) else runAction(c.action!!) }
            row.addView(b, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
            Motion.enter(b, n)
        }
        val tabs = tabRow ?: return
        for (k in 0 until tabs.childCount) {
            val t = tabs.getChildAt(k) as TextView
            val on = k == i
            t.setTextColor(if (on) Theme.ON_ACCENT else Theme.MUTED)
            (t.background as GradientDrawable).setColor(if (on) Theme.CYAN else Theme.PANEL)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (requestCode == 91 && granted) micTap()
        if (requestCode == 92 && granted) toggleWake()
    }

    override fun onPause() {
        resumed = false; ticker.removeCallbacks(tick)
        if (agent.id == "coach") Prefs.setLastChatSeen(this, System.currentTimeMillis())
        if (handsFree) { handsFree = false; voice?.stopSpeaking(); setLive(CompanionView.Activity.IDLE) }
        stopWake()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) mascot?.lookAt(ev.rawX, ev.rawY)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() { ticker.removeCallbacks(tick); stopWake(); holo?.destroy(); holo = null; voice?.shutdown(); voice = null; super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AgentProfiles.find(intent.getStringExtra(EXTRA_AGENT)) ?: AgentProfiles.all.first()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), statusBarPad(), dp(84), dp(10)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = agent.name; textSize = 20f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
        titles.addView(TextView(this).apply { text = agent.tagline; textSize = 12f; setTextColor(Theme.MUTED) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, -1, -2)
        if (agent.id == "coach") {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Theme.LINE) }
                clipToOutline = true
            }
            val holoView = HologramView(this).apply { visibility = android.view.View.GONE; setOnClickListener { applyLook((look + 1) % LOOKS) } }
            holo = holoView
            card.addView(holoView, LinearLayout.LayoutParams(-1, dp(190)))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(14), dp(8)) }
            card.addView(top, -1, -2)
            val face = CompanionView(this).apply { setOnClickListener { applyLook((look + 1) % LOOKS) } }
            mascot = face
            val orbView = OrbView(this).apply { visibility = android.view.View.GONE; setOnClickListener { applyLook((look + 1) % LOOKS) } }
            orb = orbView
            val holder = android.widget.FrameLayout(this)
            holder.addView(face, android.widget.FrameLayout.LayoutParams(-1, -1))
            holder.addView(orbView, android.widget.FrameLayout.LayoutParams(-1, -1))
            top.addView(holder, LinearLayout.LayoutParams(dp(88), dp(88)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            pnlText = TextView(this).apply { textSize = 15f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD; text = "Live PnL: loading…" }
            moodText = TextView(this).apply { textSize = 12f; setTextColor(Theme.TEXT); setPadding(0, dp(2), 0, 0) }
            deskStatus = TextView(this).apply {
                textSize = 11f; setTextColor(Theme.MUTED); setPadding(0, dp(4), 0, 0)
                text = "Desk: loading…"
                setOnClickListener { startActivity(Intent(this@AgentChatActivity, TradingActivity::class.java)) }
            }
            col.addView(pnlText); col.addView(moodText); col.addView(deskStatus)
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
            Motion.enter(card)
            applyLook(Prefs.companionChar(this).coerceIn(0, LOOKS - 1))
        }

        run {
            val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(6)) }
            fun chip(label: String, click: () -> Unit) = row.addView(smallButton(label, click), LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
            if (agent.id == "coach") {
                val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(6)) }
                tabRow = tabs
                ChatGroups.coach.forEachIndexed { i, g ->
                    tabs.addView(TextView(this).apply {
                        text = g.name; textSize = 12f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(14), dp(7), dp(14), dp(7))
                        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
                        setOnClickListener { selectGroup(i) }
                        Motion.pressFeedback(this)
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
                tabsHolder = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
                chipRow = row
            }
            if (agent.id == "research") {
                chip("Market news") { quick("What is the latest market news today and what is moving crypto, gold and the dollar?") }
                chip("Quote") { quoteDialog() }
            }
            if (agent.id != "coach") {
                chip("Learned") { notesDialog() }
                chip("Clear") { runAction("clear") }
            }
            chips.addView(row)
            tabsHolder?.let { root.addView(it, -1, -2) }
            root.addView(chips, -1, -2)
            if (agent.id == "coach") selectGroup(0)
        }
        scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(14)) }
        scroll.addView(messages, -1, -2)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        attachLabel = TextView(this).apply { textSize = 12f; setTextColor(Theme.CYAN); setPadding(dp(18), dp(4), dp(18), 0); visibility = android.view.View.GONE }
        root.addView(attachLabel, -1, -2)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(14)) }
        if (agent.acceptsImages) bar.addView(smallButton("Chart") { pickImage() })
        input = EditText(this).apply {
            hint = if (agent.acceptsImages) "Ask, or upload a chart…" else "Message ${agent.name}…"
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); textSize = 15f
            maxLines = 5
            background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Theme.LINE) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(smallButton("Send") { send() })
        root.addView(bar, -1, -2)
        setContentView(root)

        turns.addAll(AgentChatStore.load(this, agent.id))
        if (turns.isEmpty()) greet() else turns.forEach { addBubble(it.role == "user", it.text) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (deskStatus != null) { ticker.removeCallbacks(tick); tick.run() }
    }

    private fun statusBarPad(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return (if (id > 0) resources.getDimensionPixelSize(id) else dp(24)) + dp(12)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
    }

    private fun riskDialog() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val bal = field("Account balance"); val risk = field("Risk % per trade (e.g. 1)")
        val entry = field("Entry price"); val stop = field("Stop price"); val target = field("Target price (optional)")
        listOf(bal, risk, entry, stop, target).forEach { col.addView(it) }
        AlertDialog.Builder(this).setTitle("Position size").setView(col)
            .setPositiveButton("Calculate") { _, _ ->
                fun n(e: EditText) = e.text.toString().trim().toDoubleOrNull()
                val r = RiskCalc.compute(n(bal) ?: 0.0, n(risk) ?: 0.0, n(entry) ?: 0.0, n(stop) ?: 0.0, n(target))
                val msg = if (r == null) "Enter a balance, a risk % and different entry and stop prices." else String.format(java.util.Locale.US,
                    "Size: %.6g units\nRisk: %.2f (%.2f%% stop distance)\nPosition value: %.2f%s",
                    r.units, r.riskAmount, r.stopPct, r.notional, r.rrAtTarget?.let { String.format(java.util.Locale.US, "\nReward:risk %.2f", it) } ?: "")
                AlertDialog.Builder(this).setTitle("Result").setMessage(msg)
                    .setPositiveButton("Ask coach to review") { _, _ -> quick("Check this position plan against my rules:\n$msg") }
                    .setNegativeButton("Close", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun quoteDialog() {
        val box = EditText(this).apply { hint = "BTCUSDT, USDJPY, XAUUSD, SPX…"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(this).setTitle("Live quote").setView(box)
            .setPositiveButton("Get") { _, _ ->
                val sym = box.text.toString().trim()
                Thread {
                    val out = try {
                        val cryptoSym = Bybit.symbolOf(sym)
                        val fx = MarketQuotes.resolve(sym)
                        when {
                            cryptoSym != null -> String.format(java.util.Locale.US, "%s: %s (24h %+.2f%%, Bybit)", cryptoSym, Bybit.price(this, cryptoSym), Bybit.change24h(this, cryptoSym))
                            fx != null -> MarketQuotes.quote(fx.first, fx.second).text()
                            else -> "I don't recognise \"$sym\". Try a Bybit symbol, a forex pair such as EURUSD, or gold, oil, SPX."
                        }
                    } catch (e: Exception) { "Quote failed: " + (e.message ?: e.javaClass.simpleName).take(120) }
                    runOnUiThread { AlertDialog.Builder(this).setTitle("Quote").setMessage(out).setPositiveButton("OK", null).show() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun quick(message: String) { input.setText(message); send() }

    private fun smallButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        setTextColor(Theme.TEXT)
        background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(18).toFloat() }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { click() }
        Motion.pressFeedback(this)
    }

    private fun greet() {
        val hello = when (agent.id) {
            "coach" -> "Tell me your strategy in your own words, upload a chart, or say \"find me setups\". I'll judge trades against your rules and your journal.\n\nNew here? Open the Start tab for a tour, what I can do, and a setup check."
            "research" -> "Ask me anything that needs current facts. I'll search, answer first, and show sources."
            "coder" -> "Paste code or an error and tell me what you want."
            "planner" -> "Give me a goal and a deadline."
            else -> "What are we writing?"
        }
        addBubble(false, hello)
    }

    private fun addBubble(user: Boolean, text: String): TextView {
        val bubble = TextView(this).apply {
            this.text = if (user) text else Markdown.render(text); textSize = 15f
            if (!user) movementMethod = LinkMovementMethod.getInstance()
            setTextColor(if (user) Theme.ON_ACCENT else Theme.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(if (user) Theme.CYAN else Theme.PANEL); cornerRadius = dp(18).toFloat() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8); gravity = if (user) Gravity.END else Gravity.START
            if (user) marginStart = dp(48) else marginEnd = dp(48)
        }
        messages.addView(bubble, lp)
        Motion.enter(bubble)
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        return bubble
    }

    /** A proposal only. Nothing is sent to Bybit until the user taps Place and confirms the final risk summary. */
    private fun addTradeCard(t: TradeProposals.Trade) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.CYAN) }
        }
        card.addView(TextView(this).apply { text = "TRADE PROPOSAL · " + Bybit.modeText(this@AgentChatActivity); textSize = 11f; setTextColor(Theme.CYAN); typeface = Typeface.DEFAULT_BOLD })
        card.addView(TextView(this).apply { text = t.summary() + (if (t.note.isNotBlank()) "\n" + t.note else ""); textSize = 14f; setTextColor(Theme.TEXT); setPadding(0, dp(4), 0, dp(8)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val status = TextView(this).apply { textSize = 13f; setTextColor(Theme.MUTED); visibility = android.view.View.GONE }
        row.addView(smallButton("Place order") {
            status.visibility = android.view.View.VISIBLE; status.text = "Checking limits…"
            Thread {
                val result = Trading.placeProposal(this, t) { question ->
                    val latch = java.util.concurrent.CountDownLatch(1); var ok = false
                    runOnUiThread {
                        AlertDialog.Builder(this).setTitle("Confirm order").setMessage(question)
                            .setPositiveButton("Place") { _, _ -> ok = true; latch.countDown() }
                            .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                            .setOnCancelListener { latch.countDown() }.show()
                    }
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS); ok
                }
                if (result.startsWith("Order placed")) AgentLessons.addTagged(this, "coach", "trade", result.take(200))
                runOnUiThread { status.text = result }
            }.start()
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        row.addView(smallButton("Dismiss") { messages.removeView(card) }, LinearLayout.LayoutParams(-2, dp(38)))
        card.addView(row)
        card.addView(status)
        messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        Motion.enter(card)
    }

    private fun deskCard(title: String, body: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.CYAN) }
        }
        card.addView(TextView(this).apply { text = title; textSize = 11f; setTextColor(Theme.CYAN); typeface = Typeface.DEFAULT_BOLD })
        card.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(Theme.TEXT); setPadding(0, dp(4), 0, dp(8)) })
        val status = TextView(this).apply { textSize = 13f; setTextColor(Theme.MUTED); visibility = android.view.View.GONE }
        return card to status
    }

    private fun addStrategyCard(st: StrategySpec) {
        val (card, status) = deskCard("STRATEGY PROPOSAL · your playbook",
            "${st.name}\n${st.instruments.ifEmpty { listOf("any instrument") }.joinToString(", ")} · ${st.timeframes.ifEmpty { listOf("any timeframe") }.joinToString(", ")} · " +
                "${st.setupTypes.ifEmpty { listOf("any setup") }.joinToString(", ")} · min R:R ${st.minRewardToRisk}" + (if (st.notes.isNotBlank()) "\n${st.notes}" else ""))
        fun say(t: String) { status.visibility = android.view.View.VISIBLE; status.text = t }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("Accept") { TradingDesk.accept(this, st); say("Added to your playbook. I will track results and watch for matches.") }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        row.addView(smallButton("Accept & match") {
            TradingDesk.accept(this, st); say("Added. Matching now…")
            Thread { val r = try { TradingDesk.scan(this, st.instruments.ifEmpty { TradingDesk.followSymbols(this) }) } catch (e: Exception) { "Match failed: " + (e.message ?: "") }; runOnUiThread { say(r) } }.start()
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        row.addView(smallButton("Dismiss") { TradingDesk.dismissPending(this, st.name); messages.removeView(card) }, LinearLayout.LayoutParams(-2, dp(38)))
        card.addView(row); card.addView(status)
        messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        Motion.enter(card)
    }

    /** Practice trade (no real money), or the same idea sent to Deriv after the risk gate and a confirmation. */
    private fun addPaperCard(p: DeskProposals.Paper) {
        val (card, status) = deskCard("PRACTICE TRADE · no real money", p.summary() + (if (p.note.isNotBlank()) "\n" + p.note else ""))
        fun say(t: String) { status.visibility = android.view.View.VISIBLE; status.text = t }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("Practise it") {
            say("Opening…")
            Thread {
                val args = org.json.JSONObject().put("action", "open").put("symbol", p.symbol).put("side", if (p.long) "buy" else "sell").put("usd", p.usd).put("stop", p.stop).put("setup_type", "coach")
                p.target?.let { args.put("target", it) }
                val r = TradingDesk.run(this, "paper_trade", args)
                runOnUiThread { say(r) }
            }.start()
        }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        if (DerivDesk.hasToken(this) && DerivProtocol.symbolFor(p.symbol) != null) {
            row.addView(smallButton("Send to Deriv") {
                say("Checking limits…")
                Thread {
                    val r = DerivDesk.trade(this, p) { question ->
                        val latch = java.util.concurrent.CountDownLatch(1); var ok = false
                        runOnUiThread {
                            AlertDialog.Builder(this).setTitle("Confirm Deriv trade").setMessage(question)
                                .setPositiveButton("Place") { _, _ -> ok = true; latch.countDown() }
                                .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                                .setOnCancelListener { latch.countDown() }.show()
                        }
                        latch.await(120, java.util.concurrent.TimeUnit.SECONDS); ok
                    }
                    runOnUiThread { say(r) }
                }.start()
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
        }
        row.addView(smallButton("Dismiss") { messages.removeView(card) }, LinearLayout.LayoutParams(-2, dp(38)))
        card.addView(row); card.addView(status)
        messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        Motion.enter(card)
    }

    private fun pickImage() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(pick, REQUEST_IMAGE) } catch (_: Exception) { Toast.makeText(this, "No image picker is available.", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val encoded = try { encodeImage(uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (encoded == null) {
                    Toast.makeText(this, "Could not read that image. Try a screenshot or a smaller picture.", Toast.LENGTH_LONG).show()
                } else {
                    pendingImage = encoded
                    attachLabel.text = "Chart attached · it will be sent with your next message (tap to remove)"
                    attachLabel.visibility = android.view.View.VISIBLE
                    attachLabel.setOnClickListener { pendingImage = null; attachLabel.visibility = android.view.View.GONE }
                }
            }
        }.start()
    }

    /** Downscale to at most 1400 px and JPEG-compress so uploads stay small. */
    private fun encodeImage(uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2800) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = 1400f / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (bitmap !== decoded) decoded.recycle()
        bitmap.recycle()
        if (out.size() == 0 || out.size() > 5 * 1024 * 1024) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * A direct "run it" in Code Agent must invoke a real runtime, not another text-only AI reply.
     * Only a prior Python code block is eligible; the user must explicitly confirm execution.
     * Termux is not a sandbox, so suspicious filesystem, network, process, and dynamic-execution
     * APIs are refused rather than silently run.
     */
    private fun coderNotice(message: String) {
        addBubble(false, message)
        turns.add(AgentRunner.Turn("assistant", message))
        AgentChatStore.save(this, agent.id, turns)
    }

    private fun runPreviousPython(code: String) {
        val source = code.trim()
        if (source.isBlank() || source.length > 12000) {
            coderNotice("I couldn't run that: the previous Python block is empty or too large.")
            return
        }
        val risky = Regex("""(?i)\b(import\s+(os|sys|subprocess|socket|requests|urllib|shutil|pathlib|ctypes|pickle)|from\s+(os|sys|subprocess|socket|requests|urllib|shutil|pathlib|ctypes|pickle)\b|open\s*\(|eval\s*\(|exec\s*\(|__import__|os\.|subprocess\.|socket\.|requests\.|urllib\.|shutil\.)""")
        if (risky.containsMatchIn(source)) {
            coderNotice("I stopped before running this code because it uses file, network, process, or dynamic-execution features that need a separate security review. I haven't executed it.")
            return
        }
        if (!ShellRunner.termuxInstalled(this)) {
            coderNotice("I found the Python code, but Python execution needs Termux with Python installed. Nothing was run. Install/open Termux, install Python there, then ask me to run it again.")
            return
        }
        // Safe, bounded Python snippets run automatically. Do not show a per-task
        // approval card: the user has already explicitly asked "run it". Android/Termux's
        // one-time OS permission remains required and cannot be bypassed by an app.
        busy = true
        val waiting = addBubble(false, "Running Python in Termux…")
        val encoded = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Thread {
            val result = try {
                ShellRunner.runTermux(this, "printf '%s' '$encoded' | base64 -d > orion_agent_run.py && python3 orion_agent_run.py", 60)
            } catch (e: Exception) {
                "error: " + (e.message ?: e.javaClass.simpleName).take(180)
            }
            runOnUiThread {
                val shown = if (result.startsWith("error", true)) "Execution failed; no success is claimed.\n$result" else "Execution result:\n$result"
                waiting.text = Markdown.render(shown)
                turns.add(AgentRunner.Turn("assistant", shown))
                AgentChatStore.save(this, agent.id, turns)
                busy = false
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }.start()
    }

    /** Cross-fades the live assistant status into its final response so long-running tool calls feel connected, not frozen. */
    private fun transitionReply(view: TextView, value: String) {
        view.animate().cancel()
        view.animate().alpha(0.35f).setDuration(100).withEndAction {
            view.text = Markdown.render(value)
            view.alpha = 0.35f
            view.animate().alpha(1f).setDuration(190)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }.start()
    }

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        val image = pendingImage
        if (text.isEmpty() && image == null) return
        if (agent.id == "coder" && image == null &&
            Regex("""(?i)^\s*(run it|run this|execute it|execute this|test it|test this|run the code|execute the code)\s*[.!]?\s*$""").matches(text)) {
            val previous = turns.lastOrNull { it.role == "assistant" }?.text.orEmpty()
            val code = Regex("""(?s)\x60\x60\x60(?:python|py)\s*\n(.*?)\x60\x60\x60""", RegexOption.IGNORE_CASE)
                .find(previous)?.groupValues?.getOrNull(1)
            input.setText("")
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
            addBubble(true, text)
            turns.add(AgentRunner.Turn("user", text))
            AgentChatStore.save(this, agent.id, turns)
            if (code != null) runPreviousPython(code)
            else coderNotice("I couldn't find a Python code block in my previous reply, so I haven't run anything. Ask me to write or correct the code first.")
            return
        }
        if (image == null && SharedToolSurfacePolicy.shouldDelegate(text)) {
            val service = PhoneAiService.instance
            input.setText("")
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
            addBubble(true, text)
            turns.add(AgentRunner.Turn("user", text))
            val waiting = addBubble(false, "Handing this request to Orion's shared tool executor…")
            busy = true
            AgentChatStore.save(this, agent.id, turns)
            if (service == null) {
                val message = "I can't run this request from the specialist chat because Orion's accessibility service is not connected. Enable Orion's service and try again. No action was performed."
                transitionReply(waiting, message)
                turns.add(AgentRunner.Turn("assistant", message))
                AgentChatStore.save(this, agent.id, turns)
                busy = false
                return
            }
            service.executeSharedToolRequest(text, "specialist agent " + agent.name) { result ->
                val shownResult = result.ifBlank { "The tool executor returned no final response. Check Orion's task transcript." }
                transitionReply(waiting, shownResult)
                turns.add(AgentRunner.Turn("assistant", shownResult))
                AgentChatStore.save(this, agent.id, turns)
                busy = false
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
            return
        }

        input.setText("")
        pendingImage = null
        attachLabel.visibility = android.view.View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        val shown = if (image != null) "[chart attached] $text" else text
        addBubble(true, shown.trim())
        val waiting = addBubble(false, "Thinking…")
        busy = true
        setLive(CompanionView.Activity.THINKING)
        val history = turns.toList()
        Thread {
            var trades: List<TradeProposals.Trade> = emptyList()
            var strategies: List<StrategySpec> = emptyList()
            var papers: List<DeskProposals.Paper> = emptyList()
            val reply = try { AgentRunner.runFull(this, agent, history, text, image).also { trades = it.trades; strategies = it.strategies; papers = it.papers }.text }
                catch (e: Exception) { "I couldn't answer: " + (e.message ?: e.javaClass.simpleName).take(220) }
            runOnUiThread {
                trades.forEach { addTradeCard(it) }
                strategies.forEach { addStrategyCard(it) }
                papers.forEach { addPaperCard(it) }
                transitionReply(waiting, reply.ifBlank { "No answer came back. Check your AI provider in Settings." })
                setLive(CompanionView.Activity.IDLE)
                val talk = agent.id == "coach" && (Prefs.companionVoice(this) || handsFree) && reply.isNotBlank()
                if (talk) voiceOut().speak(reply.take(400)) { runOnUiThread { if (handsFree && resumed) micTap() } }
                else if (handsFree && resumed) ticker.postDelayed({ if (handsFree && resumed && !busy) micTap() }, 600L)
                else ticker.postDelayed({ startWake() }, 1500L)
                // The image is not stored in history; only the text turn is.
                turns.add(AgentRunner.Turn("user", shown.trim()))
                turns.add(AgentRunner.Turn("assistant", reply.ifBlank { "No answer came back." }))
                AgentChatStore.save(this, agent.id, turns)
                busy = false
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun notesDialog() {
        val notes = AgentLessons.list(this, agent.id)
        if (notes.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Nothing learned yet")
                .setMessage("When you state a rule or preference (\"I never trade during news\", \"always give me the stop first\"), ${agent.name} keeps it here. You can delete any note.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("${agent.name} has learned (tap to delete)")
            .setItems(notes.toTypedArray()) { _, which ->
                AlertDialog.Builder(this).setTitle("Delete this note?").setMessage(notes[which])
                    .setPositiveButton("Delete") { _, _ -> AgentLessons.delete(this, agent.id, which); notesDialog() }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }
}
