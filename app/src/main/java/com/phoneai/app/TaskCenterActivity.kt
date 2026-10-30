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
    private fun showChoiceSheet(titleText: String, subtitle: String, options: List<String>, onChoose: (Int) -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2212121.toInt(), 0xF00F0F0F.toInt())).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x555F5F5F) }
        }
        body.addView(label("TASKS", 10f, Theme.MUTED, true))
        body.addView(label(titleText, 21f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, 0) })
        body.addView(label(subtitle, 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialogRef: AlertDialog? = null
        options.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xE6222222.toInt(), 0xE8131313.toInt())).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), 0x445F5F5F)
                }
                isClickable = true; isFocusable = true; contentDescription = option
            }
            row.addView(label(option, 14f, Theme.TEXT, true))
            row.setOnClickListener { dialogRef?.dismiss(); onChoose(index) }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        val scroll = ScrollView(this).apply { addView(rows, -1, -2) }
        body.addView(scroll, LinearLayout.LayoutParams(-1,
            (resources.displayMetrics.heightPixels - dp(300)).coerceAtLeast(dp(180)).coerceAtMost(dp(450))))
        val dialog = AlertDialog.Builder(this).setView(body).setNegativeButton("CLOSE", null).create()
        dialogRef = dialog
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun panel(fill: Int = Theme.PANEL) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(fill, 0xE8131313.toInt())
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), 0x445F5F5F)
    }
    private fun button(caption: String, primary: Boolean = false, click: () -> Unit) =
        Button(this).apply {
            text = caption; isAllCaps = false; textSize = 12f
            setTextColor(if (primary) Theme.PANEL else Theme.TEXT)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (primary) intArrayOf(0xFFB3B3B3.toInt(), 0xFF8E8E8E.toInt())
                else intArrayOf(0xE6262626.toInt(), 0xD1141414.toInt())
            ).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (primary) 0xFFBFBFBF.toInt() else 0x555F5F5F)
            }
            elevation = 0f
            stateListAnimator = null
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0x00000000)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(OrionIconView(this).apply {
            icon = "tasks"
            tint = Theme.CYAN
            contentDescription = "Task centre"
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("TASK CENTRE", 11f, Theme.CYAN, true))
        title.addView(label("Your work, in one place.", 24f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, 0) })
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("＋  New task", true) { addTask() })
        page.addView(top)
        add(page, label("Task log: review queued, running, completed, failed and cancelled tasks. Status is saved on this phone.", 13f, Theme.MUTED), 8)

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11))
            background = panel(Theme.PANEL)
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
            "completed" to "Completed", "failed" to "Failed", "cancelled" to "Cancelled").forEach { pair ->
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
        summary.text = queued.toString() + " queued    ·    " + running + " running    ·    " + paused + " paused" +
            "\n" + completed + " completed    ·    " + failed + " failed" +
            if (cancelled > 0) "    ·    " + cancelled + " cancelled" else ""

        listHost.removeAllViews()
        val visible = tasks.filter { t ->
            when (filter) {
                "queued" -> t.status == "queued" || t.status == "open"
                "running" -> t.status == "running" || t.status == "in_progress"
                "paused" -> t.status == "paused"
                "completed" -> t.status == "completed" || t.status == "done"
                "failed" -> t.status == "failed"
                "cancelled" -> t.status == "cancelled"
                else -> true
            }
        }
        if (visible.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28)); background = panel()
            }
            empty.addView(OrionIconView(this).apply {
                icon = "tasks"; tint = Theme.MUTED; contentDescription = null; alpha = 0.8f
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER })
            empty.addView(label(if (filter == "all") "Nothing here yet" else "No " + filter + " tasks",
                17f, Theme.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            empty.addView(label("Create a task to start building your task history.", 12f, Theme.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            add(listHost, empty, 8)
        } else visible.forEachIndexed { index, task ->
            val card = taskCard(task)
            card.alpha = 0f
            card.translationY = dp(7).toFloat()
            add(listHost, card, 10)
            card.animate().alpha(1f).translationY(0f).setStartDelay((index.coerceAtMost(6) * 28L))
                .setDuration(210).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }

        val checkpoint = AgentCheckpointStore.pending(this)
        if (checkpoint != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
                background = panel(0xFF272727.toInt())
            }
            card.addView(label("SAVED AGENT SESSION · " + checkpoint.optString("status").uppercase(Locale.getDefault()),
                10f, Theme.CYAN, true))
            card.addView(label(checkpoint.optString("command", "Unfinished agent task").take(180), 14f, Theme.TEXT, true).apply {
                setPadding(0, dp(6), 0, dp(3))
            })
            card.addView(label("Checkpoint saved " + time(checkpoint.optLong("updatedAt")) +
                ". Open chat and say “continue” to resume.", 12f, Theme.MUTED))
            val uncertain = checkpoint.optJSONObject("inFlight")
            val recoveryNotice = checkpoint.optString("recoveryNotice")
            if (uncertain != null || recoveryNotice.isNotBlank()) {
                val warning = if (recoveryNotice.isNotBlank()) recoveryNotice
                    else "Last action outcome is uncertain: " + uncertain!!.optString("tool") +
                        ". Inspect the phone before retrying; Orion must not repeat it blindly."
                add(card, label("RECOVERY CHECK REQUIRED\n" + warning.take(450), 12f, 0xFFFFCF70.toInt(), true), 8)
            }
            add(card, button("Pause active run") {
                // Pause halts the live worker but keeps a resumable checkpoint.
                val service = PhoneAiService.instance
                if (service != null) service.stopCurrentTask()
                if (AgentCheckpointStore.pause(this)) {
                    Toast.makeText(this, "Run paused. Progress saved; any in-flight action must be checked before resume.", Toast.LENGTH_LONG).show()
                    refresh()
                } else {
                    Toast.makeText(this, "Could not save the paused checkpoint. Check the task before retrying.", Toast.LENGTH_LONG).show()
                }
            }, 8)
            add(card, button("Stop active run") {
                // Stop is terminal unless an in-flight action is uncertain; that case is
                // retained as recovery_required so Orion cannot silently discard its outcome.
                val service = PhoneAiService.instance
                if (service != null) service.stopCurrentTask()
                if (AgentCheckpointStore.stop(this)) {
                    Toast.makeText(this, "Run stopped. If an action was in flight, inspect its outcome before any continuation.", Toast.LENGTH_LONG).show()
                    refresh()
                } else {
                    Toast.makeText(this, "Could not save the stop state. Check the task before retrying.", Toast.LENGTH_LONG).show()
                }
            }, 8)
            add(card, button("Open chat to resume", true) {
                startActivity(Intent(this, MainChatActivity::class.java))
            }, 8)
            add(listHost, card, 16)
        }

        // Recent tool activity is deliberately separate from the task board: it is an
        // audit trail of tool names and outcomes, not a copy of private inputs or screen text.
        val events = ActionLogStore.all(this).take(12)
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panel()
        }
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logTitle.addView(label("RECENT AGENT ACTIVITY", 10f, Theme.MUTED, true))
        logTitle.addView(label(if (events.isEmpty()) "No tool actions recorded yet" else "${events.size} recent events · private details omitted",
            12f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, 0) })
        logTitle.addView(label("RESULT means Orion received a response; it does not by itself prove an external action succeeded.",
            10f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        logHeader.addView(logTitle, LinearLayout.LayoutParams(0, -2, 1f))
        if (events.isNotEmpty()) logHeader.addView(button("Clear") {
            AlertDialog.Builder(this)
                .setTitle("Clear action history?")
                .setMessage("This removes the local list of tool names and outcomes. It does not undo actions already performed.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Clear") { _, _ -> ActionLogStore.clear(this); refresh() }
                .show()
        })
        logCard.addView(logHeader)
        events.forEach { event ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(2))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(event.tool, 13f, Theme.TEXT, true))
            details.addView(label(time(event.at), 10f, Theme.MUTED).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val failed = event.outcome == "error returned"
            row.addView(label(if (failed) "ERROR" else "RESULT", 10f,
                if (failed) 0xFFFF8D8D.toInt() else Theme.MUTED, true))
            logCard.addView(row)
        }
        add(listHost, logCard, 16)
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
        block.addView(label(statusExplanation(task.status), 10f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        head.addView(block, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("⋯") { taskMenu(task) })
        card.addView(head)
        if (task.details.isNotBlank()) add(card, label(task.details, 12f, Theme.MUTED), 7)
        add(card, label((task.dueAt?.let { "Due " + time(it) } ?: "No due date") + " · Updated " + time(task.updatedAt),
            10f, Theme.MUTED), 7)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (task.status == "running" || task.status == "in_progress") {
            actions.addView(button("Ⅱ  Pause") { change(task, "paused") })
        } else if (task.status != "completed" && task.status != "done") {
            actions.addView(button(if (task.status == "paused") "▶  Resume" else "▶  Start", true) { change(task, "running") })
        }
        if (task.status != "completed" && task.status != "done") {
            actions.addView(button("Complete") { change(task, "completed") }.apply { setTextColor(Theme.OK) },
                LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
            actions.addView(button("!  Fail") { change(task, "failed") }.apply { setTextColor(0xFFFF8D8D.toInt()) },
                LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(6) })
        }
        actions.setPadding(0, dp(5), 0, 0)
        val actionScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(actions, ViewGroup.LayoutParams(-2, -2))
        }
        card.addView(actionScroll, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun taskMenu(task: OrionTask) {
        showChoiceSheet("Task #${task.id}", task.title,
            listOf("Edit title/details", "Change priority", "Cancel task", "Delete permanently"),
            { which ->
                when (which) {
                    0 -> editTask(task)
                    1 -> showChoiceSheet("Task priority", "Choose urgency for this task.",
                        listOf("low", "normal", "high", "urgent")) { p ->
                        if (TaskStore.update(this, task.id, priority = listOf("low", "normal", "high", "urgent")[p])) refresh() else errorToast()
                    }
                    2 -> confirm(task, "cancelled", "Cancel this task?")
                    3 -> AlertDialog.Builder(this).setTitle("Delete task?")
                        .setMessage("Task #" + task.id + " will be permanently removed from this phone.")
                        .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            if (TaskStore.delete(this, task.id)) refresh() else errorToast()
                        }.show()
                }
            })
    }

    private fun addTask() {
        val title = EditText(this).apply { hint = "What needs to get done?"; isSingleLine = true }
        val details = EditText(this).apply { hint = "Notes or next step (optional)" }
        var selectedPriority = "normal"
        lateinit var priorityButton: Button
        priorityButton = button("PRIORITY  ·  NORMAL") {
            showChoiceSheet("Task priority", "Choose how urgently this should be handled.", listOf("low", "normal", "high", "urgent")) {
                selectedPriority = listOf("low", "normal", "high", "urgent")[it]
                priorityButton.text = "PRIORITY  ·  " + selectedPriority.uppercase(Locale.ROOT)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0F0F0F.toInt(), 0xFF161616.toInt()))
            addView(label("ORION  /  TASK CENTRE", 10f, Theme.CYAN, true))
            addView(label("Create a task", 20f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, dp(8)) })
            addView(title)
            addView(details, LinearLayout.LayoutParams(-1, dp(82)).apply { topMargin = dp(8) })
            addView(priorityButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val dialog = AlertDialog.Builder(this).setView(box)
            .setNegativeButton("CANCEL", null).setPositiveButton("SAVE TASK", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val task = TaskStore.add(this, title.text.toString(), details.text.toString(), selectedPriority)
                if (task == null) title.error = "Enter a title and check storage"
                else { dialog.dismiss(); filter = "all"; refresh() }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
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
    private fun statusExplanation(status: String) = when (status) {
        "queued", "open" -> "Saved and waiting; this task is not executing."
        "running", "in_progress" -> "Marked running on the task board; check the agent session for live execution."
        "paused" -> "Paused; saved task details remain available to resume."
        "completed", "done" -> "Marked complete here; confirm the actual goal in its evidence or result."
        "failed" -> "Marked failed; inspect the activity log before retrying."
        "cancelled" -> "Cancelled; no further task-board progress is expected."
        else -> "Status is saved locally; inspect the task session for execution evidence."
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
