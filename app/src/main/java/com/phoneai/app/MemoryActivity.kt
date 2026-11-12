package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar
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
    private fun panel() = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.LINE) }
    private fun button(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.TEXT); gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(11).toFloat(); setStroke(dp(1), Theme.LINE_STRONG) }
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG); setPadding(dp(18), dp(18), dp(18), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titles.addView(text("ORION  /  LOCAL MEMORY", 10f, Theme.MUTED, true))
        titles.addView(text("Memory", 28f, Theme.TEXT, true).apply { setPadding(0, dp(3), 0, dp(2)) })
        count = text("Private notes stored on this device", 12f, Theme.MUTED); titles.addView(count); top.addView(titles)
        top.addView(button("＋ Add") { editMemory(null) }); root.addView(top)
        root.addView(text("Search saved facts, preferences, decisions and project notes.", 13f, Theme.MUTED).apply { setPadding(0, dp(12), 0, dp(10)) })
        val search = EditText(this).apply {
            hint = "Search local memory"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); textSize = 14f; setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setOnEditorActionListener { v, _, _ -> v.clearFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true }
            setPadding(dp(13), dp(11), dp(13), dp(11)); background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Theme.LINE) }
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
        renderKnowledgeGraph()
        if (all.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(28), dp(20), dp(28)); background = panel() }
            empty.addView(text(if (query.isBlank()) "Nothing saved yet" else "No matching memories", 18f, Theme.TEXT, true).apply { gravity = Gravity.CENTER })
            empty.addView(text("Use ＋ Add to save something Orion should remember.", 13f, Theme.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            page.addView(empty, LinearLayout.LayoutParams(-1, -2)); return
        }
        all.forEach { memory ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); background = panel() }
            val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            meta.addView(text(memory.category.uppercase(Locale.ROOT), 10f, Theme.TEXT_SECONDARY, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (memory.pinned) meta.addView(text("PINNED", 10f, Theme.SILVER, true).apply { setPadding(0, 0, dp(8), 0) })
            meta.addView(text("#${memory.id}", 10f, Theme.MUTED)); card.addView(meta)
            card.addView(text(memory.text, 14f, Theme.TEXT).apply { setPadding(0, dp(7), 0, dp(7)) })
            card.addView(text("SOURCE: ${memory.sourceRole.uppercase(Locale.ROOT)}  ·  CONFIDENCE: ${(memory.confidence * 100).toInt()}%  ·  ${memory.reviewStatus.uppercase(Locale.ROOT)}" +
                if (memory.sensitivity == "sensitive") "  ·  SENSITIVE (excluded from AI context)" else "",
                10f, if (memory.reviewStatus == "pending" || memory.sensitivity == "sensitive") Theme.WARN else Theme.MUTED)
                .apply { setPadding(0, 0, 0, dp(9)) })
            val expiry = OrionMemoryLifecycle.expiryFor(this, memory.id)
            if (expiry > 0L) {
                card.addView(text(
                    "EXPIRES: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expiry)),
                    10f, Theme.TEXT_SECONDARY
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
            if (memory.category == "routine" && memory.reviewStatus == "approved") {
                val reminderActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                reminderActions.addView(button("Reminder") { chooseRoutineReminder(memory.id) })
                reminderActions.addView(button("Cancel reminder") {
                    AlertDialog.Builder(this).setTitle("Cancel routine reminder?")
                        .setMessage("Orion will cancel the scheduled reminder for this routine.")
                        .setNegativeButton("Keep", null)
                        .setPositiveButton("Cancel reminder") { _, _ ->
                            OrionRoutineReminderWorker.cancel(this, memory.id)
                            Toast.makeText(this, "Routine reminder cancelled", Toast.LENGTH_SHORT).show()
                        }.show()
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                card.addView(reminderActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            }
            val governanceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            governanceActions.addView(button("Version history") { showVersionHistory(memory.id) })
            governanceActions.addView(button("Expiry") { chooseExpiry(memory.id) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            card.addView(governanceActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            card.alpha = 0f; card.translationY = dp(6).toFloat(); card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }


    /** Visible health and relationship view for the same SQLite graph used by automatic recall. */
    private fun renderKnowledgeGraph() {
        val snapshot = runCatching { OrionKnowledgeGraph.snapshot(this) }.getOrElse {
            OrionKnowledgeGraph.Snapshot(0, 0, 0, 0, emptyList(), emptyList())
        }
        val approved = MemoryStore.all(this).count {
            MemoryGovernancePolicy.canUseInPrompt(it.reviewStatus, it.sensitivity) &&
                OrionMemoryLifecycle.isActive(this, it.id)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panel()
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("CEREBRO  /  ENTITY KNOWLEDGE GRAPH", 10f, Theme.SILVER, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("ON DEVICE", 9f, Theme.MUTED, true))
        card.addView(heading)
        card.addView(text(
            "${snapshot.indexedMemories} / $approved approved memories indexed  ·  ${snapshot.entities} entities  ·  ${snapshot.relationships} facts  ·  ${snapshot.terms} search terms",
            11f, Theme.TEXT_SECONDARY
        ).apply { setPadding(0, dp(8), 0, dp(9)) })
        val graphText = if (query.isNotBlank()) {
            runCatching { KnowledgeGraph.promptBlock(this, query) }.getOrDefault("")
        } else ""
        val visibleFacts = graphText.lineSequence().filter { it.isNotBlank() }.take(5).toList()
            .ifEmpty { snapshot.recentFacts.take(5) }
        if (visibleFacts.isNotEmpty()) {
            card.addView(text(if (query.isBlank()) "RECENT RELATIONSHIPS" else "RELATIONSHIPS RELEVANT TO SEARCH", 9f, Theme.MUTED, true)
                .apply { setPadding(0, 0, 0, dp(5)) })
            visibleFacts.forEach { fact ->
                card.addView(text("•  ${fact.removePrefix("- ").trim()}", 12f, Theme.TEXT)
                    .apply { setPadding(0, dp(3), 0, dp(3)) })
            }
        }
        if (snapshot.recentEntities.isNotEmpty()) {
            card.addView(text("CONNECTED ENTITIES", 9f, Theme.MUTED, true)
                .apply { setPadding(0, dp(8), 0, dp(4)) })
            card.addView(text(snapshot.recentEntities.joinToString("   ·   "), 11f, Theme.TEXT_SECONDARY))
        }
        if (visibleFacts.isEmpty() && snapshot.recentEntities.isEmpty()) {
            card.addView(text(
                "No connected facts yet. Approve memories mentioning people, places, projects or dates; Orion will index them for recall.",
                12f, Theme.MUTED
            ))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(button("Rebuild graph") {
            val indexed = runCatching { OrionKnowledgeGraph.rebuild(this) }.getOrDefault(-1)
            Toast.makeText(this, if (indexed >= 0) "Graph rebuilt · $indexed memories indexed" else "Graph rebuild failed", Toast.LENGTH_SHORT).show()
            render()
        })
        actions.addView(button("Explore") { showGraphLookup() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7) })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun showGraphLookup() {
        val input = EditText(this).apply {
            hint = "Person, place, project or entity"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Explore knowledge graph")
            .setMessage("Find approved facts and connected memories stored on this phone.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Explore") { _, _ ->
                val entity = input.text.toString().trim()
                if (entity.isNotBlank()) {
                    val facts = runCatching { KnowledgeGraph.query(this, entity) }
                        .getOrDefault("Could not query graph relationships.")
                    val connected = runCatching { OrionKnowledgeGraph.relatedMemories(this, entity, 8) }
                        .getOrDefault(emptyList())
                    val sources = if (connected.isEmpty()) "No connected approved memories found."
                        else connected.joinToString("\n\n") { "• ${it.category.uppercase(Locale.ROOT)} — ${it.text}" }
                    AlertDialog.Builder(this).setTitle(entity)
                        .setMessage("$facts\n\nCONNECTED MEMORIES\n$sources")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun chooseRoutineReminder(memoryId: Int) {
        if (!OrionSensorConsent.read(this, OrionSensorScope.ROUTINE_REMINDERS).active()) {
            AlertDialog.Builder(this).setTitle("Routine reminders are disabled")
                .setMessage("Enable the separate Routine reminders grant first. Orion will not schedule anything until you choose a time.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open permissions") { _, _ ->
                    startActivity(Intent(this, SensorContextActivity::class.java))
                }.show()
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            AlertDialog.Builder(this).setTitle("Notifications are disabled")
                .setMessage("Android notifications are disabled for Orion. Enable them before scheduling a reminder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }
                }.show()
            return
        }
        val now = Calendar.getInstance()
        val selected = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            selected.set(Calendar.YEAR, year)
            selected.set(Calendar.MONTH, month)
            selected.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(this, { _, hour, minute ->
                selected.set(Calendar.HOUR_OF_DAY, hour)
                selected.set(Calendar.MINUTE, minute)
                selected.set(Calendar.SECOND, 0)
                selected.set(Calendar.MILLISECOND, 0)
                if (selected.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Choose a future time for the reminder.", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Repeat this reminder?")
                        .setItems(arrayOf("Once", "Every day", "Every week")) { _, repeat ->
                            val days = when (repeat) { 1 -> 1; 2 -> 7; else -> 0 }
                            val ok = OrionRoutineReminderWorker.schedule(this, memoryId, selected.timeInMillis, days)
                            Toast.makeText(this, if (ok) "Routine reminder scheduled" else "Could not schedule reminder; check the routine and permission grant.", Toast.LENGTH_SHORT).show()
                        }.show()
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis()
        }.show()
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
                append("\nCategory: ${version.optString("category")}")
                append(" · Confidence: $confidence%")
                append(" · Status: ${version.optString("reviewStatus")}")
                append("\nSource: ${version.optString("sourceRole")} · Reason: ${version.optString("reason")}")
                append("\n")
                append(version.optString("text").take(300))
            }
        }
        AlertDialog.Builder(this).setTitle("Previous versions · ${versions.size}")
            .setMessage(details.joinToString("\n\n"))
            .setPositiveButton("Close", null).show()
    }

    private fun chooseExpiry(id: Int) {
        val current = OrionMemoryLifecycle.expiryFor(this, id)
        val currentLabel = if (current == 0L) "No expiry" else
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current))
        val options = arrayOf("Never expire", "7 days", "30 days", "90 days", "1 year")
        AlertDialog.Builder(this)
            .setTitle("Memory expiry")
            .setMessage("Current expiry: $currentLabel\nChoose when Orion should stop using this memory in context.")
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
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(4)); setBackgroundColor(Theme.PANEL) }
        body.addView(text(if (existing == null) "Save a memory" else "Edit memory", 20f, Theme.TEXT, true))
        body.addView(text("Keep it short and specific. This is stored locally on this phone.", 12f, Theme.MUTED).apply { setPadding(0, dp(5), 0, dp(10)) })
        val category = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MemoryActivity, android.R.layout.simple_spinner_dropdown_item, MemoryStore.CATEGORIES)
            setSelection(MemoryStore.CATEGORIES.indexOf(existing?.category ?: "fact").coerceAtLeast(0))
        }; body.addView(category)
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty()); hint = "What should Orion remember?"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
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
