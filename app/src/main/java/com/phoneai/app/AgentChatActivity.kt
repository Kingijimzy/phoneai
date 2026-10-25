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
    private val turns = mutableListOf<AgentRunner.Turn>()
    private var pendingImage: String? = null
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AgentProfiles.find(intent.getStringExtra(EXTRA_AGENT)) ?: AgentProfiles.all.first()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(36), dp(12), dp(10)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = agent.name; textSize = 20f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
        titles.addView(TextView(this).apply { text = agent.tagline; textSize = 12f; setTextColor(Theme.MUTED) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(smallButton("Learned") { notesDialog() })
        header.addView(smallButton("Clear") { turns.clear(); AgentChatStore.clear(this, agent.id); messages.removeAllViews(); greet() })
        root.addView(header, -1, -2)

        if (agent.id == "coach") {
            val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(6)) }
            fun chip(label: String, click: () -> Unit) = row.addView(smallButton(label, click), LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8) })
            chip("Find setups") { quick("Find me the best trade setups on my watchlist right now and judge them against my strategy.") }
            chip("Market outlook") { quick("Give me your outlook for my watchlist: bias, key levels and what would invalidate it.") }
            chip("Daily briefing") { quick("Give me my trading briefing: outlook for my watchlist, the best setups, and one risk to watch today.") }
            chip("Review my trade") { input.setText("Review my trade: pair, side, entry, stop, exit and why I took it: "); input.setSelection(input.text.length) }
            chip("What do you remember?") { notesDialog() }
            chips.addView(row)
            root.addView(chips, -1, -2)
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
            this.text = text; textSize = 15f
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

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        val image = pendingImage
        if (text.isEmpty() && image == null) return
        input.setText("")
        pendingImage = null
        attachLabel.visibility = android.view.View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        val shown = if (image != null) "[chart attached] $text" else text
        addBubble(true, shown.trim())
        val waiting = addBubble(false, "Thinking…")
        busy = true
        val history = turns.toList()
        Thread {
            val reply = try { AgentRunner.run(this, agent, history, text, image) }
                catch (e: Exception) { "I couldn't answer: " + (e.message ?: e.javaClass.simpleName).take(220) }
            runOnUiThread {
                waiting.text = reply.ifBlank { "No answer came back. Check your AI provider in Settings." }
                // The image is not stored in history; only the text turn is.
                turns.add(AgentRunner.Turn("user", shown.trim()))
                turns.add(AgentRunner.Turn("assistant", waiting.text.toString()))
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
