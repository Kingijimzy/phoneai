package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Searchable, editable view of Orion's on-device entity/relation graph. */
class KnowledgeGraphActivity : Activity() {
    private lateinit var host: LinearLayout
    private lateinit var query: EditText
    private lateinit var count: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
        }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF171717.toInt()); cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0xFF353535.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) }
    }
    private fun button(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFEAEAEA.toInt()); gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(0xFF252525.toInt()); cornerRadius = dp(11).toFloat()
            setStroke(dp(1), 0xFF454545.toInt())
        }
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF090909.toInt()); isFillViewport = true }
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(28)) }
        scroll.addView(host); setContentView(scroll)
        host.addView(label("ORION / LOCAL KNOWLEDGE", 10f, 0xFFAAAAAA.toInt(), true))
        host.addView(label("Knowledge graph", 28f, 0xFFF2F2F2.toInt(), true))
        host.addView(label("Search and inspect entity → relationship → entity facts stored locally on this phone. These facts are reviewable and do not retrain the base model.", 12f, 0xFFAAAAAA.toInt()))
        query = EditText(this).apply {
            hint = "Search people, projects, preferences…"
            setSingleLine(true); setTextColor(0xFFF0F0F0.toInt()); setHintTextColor(0xFF858585.toInt())
            background = GradientDrawable().apply { setColor(0xFF202020.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF444444.toInt()) }
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        host.addView(query, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        count = label("", 11f, 0xFFAAAAAA.toInt())
        host.addView(count)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Teach graph") { teachGraph() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Refresh") { render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        actions.addView(button("Clear") { confirmClear() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        host.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
    }

    private fun render() {
        if (!::host.isInitialized) return
        val facts = runCatching { KnowledgeGraph.all(this) }.getOrDefault(emptyList())
        count.text = facts.size.toString() + " saved relationship(s) · local storage"
        // Keep the first four fixed controls and replace only the result cards.
        while (host.childCount > 6) host.removeViewAt(6)
        val q = if (::query.isInitialized) query.text?.toString()?.trim().orEmpty() else ""
        val visible = if (q.isBlank()) facts.sortedByDescending { it.lastAt }.take(100)
            else GraphPolicy.related(facts, q, 50)
        if (visible.isEmpty()) {
            host.addView(panel().apply {
                addView(label(if (facts.isEmpty()) "Your graph is empty" else "No matching relationships", 15f, 0xFFEAEAEA.toInt(), true))
                addView(label("Use Teach graph to add a statement. Orion stores only facts that pass its local extraction rules.", 12f, 0xFFAAAAAA.toInt()))
            })
        } else {
            visible.forEach { fact ->
                host.addView(panel().apply {
                    addView(label(fact.a, 11f, 0xFFAAAAAA.toInt()))
                    addView(label(fact.rel.uppercase(), 10f, 0xFFBDBDBD.toInt(), true))
                    addView(label(fact.b, 16f, 0xFFF0F0F0.toInt(), true))
                    addView(label("Observed " + fact.count + " time(s)", 10f, 0xFF858585.toInt()))
                })
            }
        }
    }

    private fun teachGraph() {
        val input = EditText(this).apply {
            hint = "Example: I prefer dark themes"
            minLines = 2; maxLines = 4
            setTextColor(0xFFEEEEEE.toInt()); setHintTextColor(0xFF888888.toInt())
        }
        AlertDialog.Builder(this).setTitle("Teach local knowledge graph").setMessage("Enter a factual statement. Questions and credential-like text are not stored.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save statement") { _, _ ->
                val n = runCatching { KnowledgeGraph.learnFrom(this, input.text.toString()) }.getOrDefault(0)
                Toast.makeText(this, if (n > 0) "Added " + n + " relationship(s)" else "No safe relationship extracted; try a clearer factual statement", Toast.LENGTH_LONG).show()
                render()
            }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Clear local knowledge graph?")
            .setMessage("This permanently removes the graph's stored relationships from this phone. It does not delete your separate memory notes or knowledge documents.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear graph") { _, _ ->
                val ok = runCatching { KnowledgeGraph.clear(this); true }.getOrDefault(false)
                Toast.makeText(this, if (ok) "Knowledge graph cleared" else "Could not clear knowledge graph", Toast.LENGTH_SHORT).show()
                render()
            }.show()
    }
}
