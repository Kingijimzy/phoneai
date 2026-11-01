package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

/** Local-first memory browser. Entries remain in app-private storage on this device. */
class MemoryActivity : Activity() {
    private var query = ""
    private lateinit var page: LinearLayout
    private lateinit var count: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun panel() = GradientDrawable().apply { setColor(0xFF171717.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFF343434.toInt()) }
    private fun button(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFE4E4E4.toInt()); gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(0xFF242424.toInt()); cornerRadius = dp(11).toFloat(); setStroke(dp(1), 0xFF444444.toInt()) }
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF090909.toInt()); setPadding(dp(18), dp(18), dp(18), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titles.addView(text("ORION  /  LOCAL MEMORY", 10f, 0xFFAAAAAA.toInt(), true))
        titles.addView(text("Memory", 28f, 0xFFF2F2F2.toInt(), true).apply { setPadding(0, dp(3), 0, dp(2)) })
        count = text("Private notes stored on this device", 12f, 0xFF929292.toInt()); titles.addView(count); top.addView(titles)
        top.addView(button("＋ Add") { editMemory(null) }); root.addView(top)
        root.addView(text("Search saved facts, preferences, decisions and project notes.", 13f, 0xFF9A9A9A.toInt()).apply { setPadding(0, dp(12), 0, dp(10)) })
        val search = EditText(this).apply {
            hint = "Search local memory"; setTextColor(0xFFF0F0F0.toInt()); setHintTextColor(0xFF777777.toInt()); textSize = 14f; setSingleLine(true)
            setPadding(dp(13), dp(11), dp(13), dp(11)); background = GradientDrawable().apply { setColor(0xFF151515.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF363636.toInt()) }
        }
        root.addView(search)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(18)) }
        scroll.addView(page); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        setContentView(root); render()
    }
    override fun onResume() { super.onResume(); if (::page.isInitialized) render() }
    private fun render() {
        page.removeAllViews()
        val all = if (query.isBlank()) MemoryStore.all(this).sortedByDescending { it.updated } else MemoryStore.search(this, query)
        count.text = "${MemoryStore.all(this).size} saved memories · stored only in Orion's app data"
        if (all.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(28)); background = panel() }
            empty.addView(text(if (query.isBlank()) "Nothing saved yet" else "No matching memories", 18f, 0xFFECECEC.toInt(), true).apply { gravity = Gravity.CENTER })
            empty.addView(text("Use ＋ Add to save something Orion should remember.", 13f, 0xFF999999.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            page.addView(empty, LinearLayout.LayoutParams(-1, -2)); return
        }
        all.forEach { memory ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); background = panel() }
            val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            meta.addView(text(memory.category.uppercase(Locale.ROOT), 10f, 0xFFBEBEBE.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            if (memory.pinned) meta.addView(text("PINNED", 10f, Theme.SILVER, true).apply { setPadding(0, 0, dp(8), 0) })
            meta.addView(text("#${memory.id}", 10f, 0xFF777777.toInt())); card.addView(meta)
            card.addView(text(memory.text, 14f, 0xFFEAEAEA.toInt()).apply { setPadding(0, dp(7), 0, dp(7)) })
            card.addView(text("SOURCE: ${memory.sourceRole.uppercase(Locale.ROOT)}  ·  CONFIDENCE: ${(memory.confidence * 100).toInt()}%  ·  ${memory.reviewStatus.uppercase(Locale.ROOT)}" +
                if (memory.sensitivity == "sensitive") "  ·  SENSITIVE (excluded from AI context)" else "",
                10f, if (memory.reviewStatus == "pending" || memory.sensitivity == "sensitive") 0xFFFFC27A.toInt() else 0xFF888888.toInt())
                .apply { setPadding(0, 0, 0, dp(9)) })
            val expiry = OrionMemoryLifecycle.expiryFor(this, memory.id)
            if (expiry > 0L) {
                card.addView(text(
                    "EXPIRES: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expiry)),
                    10f, 0xFFB9C7D5.toInt()
                ).apply { setPadding(0, 0, 0, dp(8)) })
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            if (memory.reviewStatus == "pending") {
                actions.addView(button("Approve") {
                    val ok = MemoryStore.setReviewStatus(this, memory.id, "approved")
                    Toast.makeText(this, if (ok) "Memory approved" else "Could not approve memory", Toast.LENGTH_SHORT).show()
                    render()
                })
                actions.addView(button("Reject") {
                    val ok = MemoryStore.setReviewStatus(this, memory.id, "rejected")
                    Toast.makeText(this, if (ok) "Memory rejected" else "Could not reject memory", Toast.LENGTH_SHORT).show()
                    render()
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            }
            actions.addView(button(if (memory.pinned) "Unpin" else "Pin") {
                val ok = MemoryStore.setPinned(this, memory.id, !memory.pinned)
                val message = if (!ok) "Could not update memory" else if (memory.pinned) "Memory unpinned" else "Memory pinned"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                render()
            })
            actions.addView(button("Edit") { editMemory(memory) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            actions.addView(button("Delete") { confirmDelete(memory.id) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            card.addView(actions)
            val governanceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            governanceActions.addView(button("Version history") { showVersionHistory(memory.id) })
            governanceActions.addView(button("Expiry") { chooseExpiry(memory.id) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            card.addView(governanceActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            card.alpha = 0f; card.translationY = dp(6).toFloat(); card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun showVersionHistory(id: Int) {
        val versions = OrionMemoryLifecycle.history(this, id)
        if (versions.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Version history")
                .setMessage("No previous versions have been archived for this memory yet. Orion archives the previous version when its content is changed or superseded.")
                .setPositiveButton("OK", null).show()
            return
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val details = versions.asReversed().mapIndexed { index, version ->
            val time = version.optLong("capturedAt", 0L)
            val confidence = (version.optDouble("confidence", 0.0).coerceIn(0.0, 1.0) * 100).toInt()
            buildString {
                append("VERSION ${versions.size - index} · ")
                append(if (time > 0L) formatter.format(Date(time)) else "unknown date")
                append("\\nCategory: ${version.optString("category")}")
                append(" · Confidence: $confidence%")
                append(" · Status: ${version.optString("reviewStatus")}")
                append("\\nSource: ${version.optString("sourceRole")} · Reason: ${version.optString("reason")}")
                append("\\n")
                append(version.optString("text").take(300))
            }
        }
        AlertDialog.Builder(this).setTitle("Previous versions · ${versions.size}")
            .setMessage(details.joinToString("\\n\\n"))
            .setPositiveButton("Close", null).show()
    }

    private fun chooseExpiry(id: Int) {
        val current = OrionMemoryLifecycle.expiryFor(this, id)
        val currentLabel = if (current == 0L) "No expiry" else
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current))
        val options = arrayOf("Never expire", "7 days", "30 days", "90 days", "1 year")
        AlertDialog.Builder(this)
            .setTitle("Memory expiry")
            .setMessage("Current expiry: $currentLabel\\nChoose when Orion should stop using this memory in context.")
            .setItems(options) { _, which ->
                val now = System.currentTimeMillis()
                val expiry = when (which) {
                    0 -> 0L
                    1 -> now + 7L * 24 * 60 * 60 * 1000
                    2 -> now + 30L * 24 * 60 * 60 * 1000
                    3 -> now + 90L * 24 * 60 * 60 * 1000
                    else -> now + 365L * 24 * 60 * 60 * 1000
                }
                val ok = OrionMemoryLifecycle.setExpiry(this, id, expiry)
                Toast.makeText(this, if (ok) "Memory expiry updated" else "Could not update expiry", Toast.LENGTH_SHORT).show()
                render()
            }.show()
    }

    private fun editMemory(existing: Memory?) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(4)); setBackgroundColor(0xFF111111.toInt()) }
        body.addView(text(if (existing == null) "Save a memory" else "Edit memory", 20f, 0xFFF0F0F0.toInt(), true))
        body.addView(text("Keep it short and specific. This is stored locally on this phone.", 12f, 0xFF999999.toInt()).apply { setPadding(0, dp(5), 0, dp(10)) })
        val category = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MemoryActivity, android.R.layout.simple_spinner_dropdown_item, MemoryStore.CATEGORIES)
            setSelection(MemoryStore.CATEGORIES.indexOf(existing?.category ?: "fact").coerceAtLeast(0))
        }; body.addView(category)
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty()); hint = "What should Orion remember?"; setTextColor(0xFFF0F0F0.toInt()); setHintTextColor(0xFF777777.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; maxLines = 6
        }; body.addView(input)
        AlertDialog.Builder(this).setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
            val ok = if (existing == null) MemoryStore.addOwnerConfirmed(this, category.selectedItem?.toString(), input.text.toString()) != null
            else MemoryStore.updateOwnerConfirmed(this, existing.id, category.selectedItem?.toString(), input.text.toString())
            Toast.makeText(this, if (ok) "Memory saved" else "Could not save memory. Check that the text is not empty.", Toast.LENGTH_SHORT).show(); render()
        }.show()
    }
    private fun confirmDelete(id: Int) {
        AlertDialog.Builder(this).setTitle("Delete this memory?").setMessage("This removes the saved note from Orion's local memory.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                val ok = MemoryStore.delete(this, id); Toast.makeText(this, if (ok) "Memory deleted" else "Could not delete memory", Toast.LENGTH_SHORT).show(); render()
            }.show()
    }
}
