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
        private const val REQUEST_IMAGE = 7422
    }

    private lateinit var agent: AgentProfile
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var attachLabel: TextView
    private lateinit var sendButton: Button
    private lateinit var activityStatus: TextView
    private val turns = mutableListOf<AgentRunner.Turn>()
    private var pendingImage: String? = null
    @Volatile private var busy = false
    @Volatile private var screenDestroyed = false

    override fun onDestroy() {
        screenDestroyed = true
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setBusyState(value: Boolean, message: String = "") {
        busy = value
        if (::sendButton.isInitialized) {
            sendButton.isEnabled = !value
            sendButton.text = if (value) "Working…" else "Send"
            sendButton.alpha = if (value) 0.55f else 1f
        }
        if (::activityStatus.isInitialized) {
            activityStatus.text = message
            activityStatus.visibility = if (message.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::input.isInitialized) outState.putString("agent_chat_draft", input.text.toString())
        if (::scroll.isInitialized) outState.putInt("agent_chat_scroll_y", scroll.scrollY)
        outState.putBoolean("agent_chat_was_busy", busy)
        super.onSaveInstanceState(outState)
    }

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

        run {
            val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(6)) }
            fun chip(label: String, click: () -> Unit) = row.addView(smallButton(label, click), LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
            if (agent.id == "coach") {
            chip("Find setups") { quick("Find me the best trade setups on my watchlist right now and judge them against my strategy.") }
            chip("Market outlook") { quick("Give me your outlook for my watchlist: bias, key levels and what would invalidate it.") }
            chip("Daily briefing") { quick("Give me my trading briefing: outlook for my watchlist, the best setups, and one risk to watch today.") }
            chip("Review my trade") { input.setText("Review my trade: pair, side, entry, stop, exit and why I took it: "); input.setSelection(input.text.length) }
            chip("Risk calculator") { riskDialog() }
            chip("Weekly review") { quick("Review my week: use my journal stats and track record, name my best and worst habits, and give me one rule to add.") }
            chip("Quote") { quoteDialog() }
            }
            if (agent.id == "research") {
                chip("Market news") { quick("What is the latest market news today and what is moving crypto, gold and the dollar?") }
                chip("Quote") { quoteDialog() }
            }
            chip("Learned") { notesDialog() }
            chip("Clear") { turns.clear(); AgentChatStore.clear(this, agent.id); messages.removeAllViews(); greet() }
            chips.addView(row)
            root.addView(chips, -1, -2)
        }
        scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(14)) }
        scroll.addView(messages, -1, -2)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        attachLabel = TextView(this).apply { textSize = 12f; setTextColor(Theme.SILVER); setPadding(dp(18), dp(4), dp(18), 0); visibility = android.view.View.GONE }
        root.addView(attachLabel, -1, -2)
        activityStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(Theme.MUTED)
            setPadding(dp(18), dp(3), dp(18), dp(2))
            visibility = android.view.View.GONE
        }
        root.addView(activityStatus, -1, -2)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(14)) }
        if (agent.acceptsImages) bar.addView(smallButton("Chart") { pickImage() })
        input = EditText(this).apply {
            hint = if (agent.acceptsImages) "Ask, or upload a chart…" else "Message ${agent.name}…"
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); textSize = 15f
            maxLines = 5
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Theme.LINE) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = smallButton("Send") { send() }
        bar.addView(sendButton)
        root.addView(bar, -1, -2)
        setContentView(root)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }

        turns.addAll(AgentChatStore.load(this, agent.id))
        if (turns.isEmpty()) greet() else turns.forEach { addBubble(it.role == "user", it.text) }
        savedInstanceState?.getString("agent_chat_draft")?.let { input.setText(it); input.setSelection(input.text.length) }
        savedInstanceState?.getInt("agent_chat_scroll_y")?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
        if (savedInstanceState?.getBoolean("agent_chat_was_busy") == true) {
            val interrupted = "The previous reply was interrupted when this screen was recreated. Your earlier messages are saved; send the request again when ready."
            addBubble(false, interrupted)
            turns.add(AgentRunner.Turn("assistant", interrupted))
            AgentChatStore.save(this, agent.id, turns)
        }
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
            "coach" -> "Tell me your strategy in your own words, upload a chart, or say \"find me setups\". I'll judge trades against your rules and your journal."
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

    private fun pickImage() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(pick, REQUEST_IMAGE) } catch (_: Exception) { Toast.makeText(this, "No image picker is available.", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val encoded = try { encodeImage(uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (screenDestroyed || isFinishing || isDestroyed) return@runOnUiThread
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
        setBusyState(true, "Running Python in Termux…")
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
                setBusyState(false)
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
            setBusyState(true, "Orion is using a shared phone tool…")
            AgentChatStore.save(this, agent.id, turns)
            if (service == null) {
                val message = "I can't run this request from the specialist chat because Orion's accessibility service is not connected. Enable Orion's service and try again. No action was performed."
                transitionReply(waiting, message)
                turns.add(AgentRunner.Turn("assistant", message))
                AgentChatStore.save(this, agent.id, turns)
                setBusyState(false)
                return
            }
            service.executeSharedToolRequest(text, "specialist agent " + agent.name) { result ->
                val shownResult = result.ifBlank { "The tool executor returned no final response. Check Orion's task transcript." }
                if (screenDestroyed || isFinishing || isDestroyed) {
                    val recovered = turns.toMutableList().apply { add(AgentRunner.Turn("assistant", shownResult)) }
                    AgentChatStore.save(applicationContext, agent.id, recovered)
                    return@executeSharedToolRequest
                }
                transitionReply(waiting, shownResult)
                turns.add(AgentRunner.Turn("assistant", shownResult))
                AgentChatStore.save(this, agent.id, turns)
                setBusyState(false)
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
        setBusyState(true, "Orion is thinking…")
        val history = turns.toList()
        Thread {
            var trades: List<TradeProposals.Trade> = emptyList()
            val reply = try { AgentRunner.runFull(this, agent, history, text, image).also { trades = it.trades }.text }
                catch (e: Exception) { "I couldn't answer: " + (e.message ?: e.javaClass.simpleName).take(220) }
            runOnUiThread {
                val finalReply = reply.ifBlank { "No answer came back. Check your AI provider in Settings." }
                if (screenDestroyed || isFinishing || isDestroyed) {
                    val recovered = history.toMutableList().apply {
                        add(AgentRunner.Turn("user", shown.trim()))
                        add(AgentRunner.Turn("assistant", finalReply))
                    }
                    AgentChatStore.save(applicationContext, agent.id, recovered)
                    return@runOnUiThread
                }
                trades.forEach { addTradeCard(it) }
                transitionReply(waiting, finalReply)
                // The image is not stored in history; only the text turn is.
                turns.add(AgentRunner.Turn("user", shown.trim()))
                turns.add(AgentRunner.Turn("assistant", reply.ifBlank { "No answer came back." }))
                AgentChatStore.save(this, agent.id, turns)
                setBusyState(false)
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
