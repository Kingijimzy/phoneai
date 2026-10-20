package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent task board. It tracks saved task status; it does not silently execute work in the background. */
class TaskCenterActivity : Activity() {
    private var filter = "all"
    private lateinit var listHost: LinearLayout
    private lateinit var summary: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun panel(fill: Int = Theme.PANEL) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Theme.LINE)
    }
    private fun button(caption: String, primary: Boolean = false, click: () -> Unit) =
        Button(this).apply {
            text = caption; isAllCaps = false; textSize = 12f
            setTextColor(if (primary) 0xFF071018.toInt() else Theme.TEXT)
            background = GradientDrawable().apply {
                setColor(if (primary) Theme.CYAN else 0xFF18212E.toInt())
                cornerRadius = dp(12).toFloat()
                if (!primary) setStroke(dp(1), Theme.LINE)
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { click() }
        }
    private fun add(parent: LinearLayout, view: View, top: Int = 8) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("TASK CENTRE", 11f, Theme.CYAN, true))
        title.addView(label("Your work, in one place.", 24f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, 0) })
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("+ New task", true) { addTask() })
        page.addView(top)
        add(page, label("Track queued work, progress, reminders and completed items. Status is saved on this phone.", 13f, Theme.MUTED), 8)

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11))
            background = panel(0xFF101B27.toInt())
        }
        note.addView(label("AGENT STATUS", 10f, Theme.CYAN, true))
        note.addView(label("This board tracks tasks; it does not silently execute queued work. Phone-control actions still run in chat, with approvals.", 12f, Theme.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        add(page, note, 13)

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)); background = panel()
        }
        summaryCard.addView(label("OVERVIEW", 10f, Theme.MUTED, true))
        summary = label("", 12f, Theme.TEXT, true)
        summaryCard.addView(summary.apply { setPadding(0, dp(7), 0, 0) })
        add(page, summaryCard, 13)

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("all" to "All", "queued" to "Queued", "running" to "Running", "paused" to "Paused",
            "completed" to "Completed", "failed" to "Failed").forEach { pair ->
            filters.addView(button(pair.second, pair.first == filter) {
                filter = pair.first; refresh()
            }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
        }
        add(page, android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filters, ViewGroup.LayoutParams(-2, -2))
        }, 13)

        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(page, listHost, 6)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::listHost.isInitialized) refresh() }

    private fun refresh() {
        if (!::listHost.isInitialized) return
        val tasks = TaskStore.all(this)
        val queued = tasks.count { it.status == "queued" || it.status == "open" }
        val running = tasks.count { it.status == "running" || it.status == "in_progress" }
        val paused = tasks.count { it.status == "paused" }
        val completed = tasks.count { it.status == "completed" || it.status == "done" }
        val failed = tasks.count { it.status == "failed" }
        val cancelled = tasks.count { it.status == "cancelled" }
        summary.text = "◉ " + queued + " queued    ↻ " + running + " running    Ⅱ " + paused +
            "\n✓ " + completed + " completed    ! " + failed + " failed" +
            if (cancelled > 0) "    × " + cancelled + " cancelled" else ""

        listHost.removeAllViews()
        val visible = tasks.filter { t ->
            when (filter) {
                "queued" -> t.status == "queued" || t.status == "open"
                "running" -> t.status == "running" || t.status == "in_progress"
                "paused" -> t.status == "paused"
                "completed" -> t.status == "completed" || t.status == "done"
                "failed" -> t.status == "failed"
                else -> true
            }
        }
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28)); background = panel()
            }
            empty.addView(label(if (filter == "all") "Nothing here yet" else "No " + filter + " tasks",
                17f, Theme.TEXT, true).apply { gravity = Gravity.CENTER })
            empty.addView(label("Create a task to start building your task history.", 12f, Theme.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            add(listHost, empty, 8)
        } else visible.forEach { add(listHost, taskCard(it), 10) }

        val checkpoint = AgentCheckpointStore.pending(this)
        if (checkpoint != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
                background = panel(0xFF142333.toInt())
            }
            card.addView(label("SAVED AGENT SESSION · " + checkpoint.optString("status").uppercase(Locale.getDefault()),
                10f, Theme.CYAN, true))
            card.addView(label(checkpoint.optString("command", "Unfinished agent task").take(180), 14f, Theme.TEXT, true).apply {
                setPadding(0, dp(6), 0, dp(3))
            })
            card.addView(label("Checkpoint saved " + time(checkpoint.optLong("updatedAt")) +
                ". Open chat and say “continue” to resume.", 12f, Theme.MUTED))
            add(card, button("Open chat to resume", true) {
                startActivity(Intent(this, MainChatActivity::class.java))
            }, 10)
            add(listHost, card, 16)
        }
    }

    private fun taskCard(task: OrionTask): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)); background = panel()
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(label("#" + task.id + "  " + task.title, 15f, Theme.TEXT, true))
        block.addView(label(statusName(task.status) + " · " + task.priority.uppercase(Locale.getDefault()),
            10f, statusColor(task.status), true).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(block, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("⋯") { taskMenu(task) })
        card.addView(head)
        if (task.details.isNotBlank()) add(card, label(task.details, 12f, Theme.MUTED), 7)
        add(card, label((task.dueAt?.let { "Due " + time(it) } ?: "No due date") + " · Updated " + time(task.updatedAt),
            10f, Theme.MUTED), 7)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (task.status == "running" || task.status == "in_progress") {
            actions.addView(button("Pause") { change(task, "paused") })
        } else if (task.status != "completed" && task.status != "done") {
            actions.addView(button(if (task.status == "paused") "Resume" else "Start", true) { change(task, "running") })
        }
        if (task.status != "completed" && task.status != "done") {
            actions.addView(button("Complete") { change(task, "completed") }.apply { setTextColor(Theme.OK) },
                LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
            actions.addView(button("Fail") { change(task, "failed") }.apply { setTextColor(0xFFFF8D8D.toInt()) },
                LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
        }
        card.addView(actions.apply { setPadding(0, dp(5), 0, 0) })
        return card
    }

    private fun taskMenu(task: OrionTask) {
        AlertDialog.Builder(this).setTitle("Task #" + task.id)
            .setItems(arrayOf("Edit title/details", "Change priority", "Cancel task", "Delete permanently")) { _, which ->
                when (which) {
                    0 -> editTask(task)
                    1 -> AlertDialog.Builder(this).setTitle("Priority").setItems(arrayOf("low", "normal", "high", "urgent")) { _, p ->
                        if (TaskStore.update(this, task.id, priority = arrayOf("low", "normal", "high", "urgent")[p])) refresh() else errorToast()
                    }.show()
                    2 -> confirm(task, "cancelled", "Cancel this task?")
                    3 -> AlertDialog.Builder(this).setTitle("Delete task?")
                        .setMessage("Task #" + task.id + " will be permanently removed from this phone.")
                        .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            if (TaskStore.delete(this, task.id)) refresh() else errorToast()
                        }.show()
                }
            }.show()
    }

    private fun addTask() {
        val title = EditText(this).apply { hint = "What needs to get done?"; isSingleLine = true }
        val details = EditText(this).apply { hint = "Notes or next step (optional)" }
        val priority = Spinner(this).apply {
            adapter = ArrayAdapter(this@TaskCenterActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("normal", "low", "high", "urgent"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(title); addView(details, LinearLayout.LayoutParams(-1, dp(82)))
            addView(label("Priority", 12f, Theme.MUTED)); addView(priority)
        }
        val dialog = AlertDialog.Builder(this).setTitle("New task").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save task", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val task = TaskStore.add(this, title.text.toString(), details.text.toString(),
                    priority.selectedItem?.toString() ?: "normal")
                if (task == null) title.error = "Enter a title and check storage"
                else { dialog.dismiss(); filter = "all"; refresh() }
            }
        }
        dialog.show()
    }

    private fun editTask(task: OrionTask) {
        val title = EditText(this).apply { setText(task.title); hint = "Task title"; isSingleLine = true }
        val details = EditText(this).apply { setText(task.details); hint = "Notes" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(title); addView(details, LinearLayout.LayoutParams(-1, dp(82)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Edit task #" + task.id).setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.toString().isBlank()) title.error = "A title is required"
                else if (TaskStore.update(this, task.id, title = title.text.toString(), details = details.text.toString())) {
                    dialog.dismiss(); refresh()
                } else errorToast()
            }
        }
        dialog.show()
    }

    private fun confirm(task: OrionTask, status: String, message: String) {
        AlertDialog.Builder(this).setMessage(message).setNegativeButton("Keep", null)
            .setPositiveButton("Confirm") { _, _ -> change(task, status) }.show()
    }
    private fun change(task: OrionTask, status: String) {
        if (TaskStore.update(this, task.id, status = status)) refresh() else errorToast()
    }
    private fun statusName(status: String) = when (status) {
        "open" -> "Queued"; "in_progress" -> "Running"; "done" -> "Completed"; "cancelled" -> "Cancelled"
        else -> status.replaceFirstChar { it.uppercase() }
    }
    private fun statusColor(status: String) = when (status) {
        "running", "in_progress" -> Theme.CYAN
        "completed", "done" -> Theme.OK
        "failed" -> 0xFFFF8D8D.toInt()
        "paused" -> 0xFFFFCF70.toInt()
        else -> Theme.MUTED
    }
    private fun time(value: Long) = if (value <= 0L) "unknown" else
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(value))
    private fun errorToast() = Toast.makeText(this, "Could not save this change. Try again.", Toast.LENGTH_SHORT).show()
}
