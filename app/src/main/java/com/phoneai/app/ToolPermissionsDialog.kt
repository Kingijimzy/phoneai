package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.view.ViewGroup

/** Autonomy control center: high-level mode, per-tool standing rules, budgets, notifications and OS grant status. */
internal fun Activity.showToolPermissions() = showAutonomyControlCenter()

internal fun Activity.showAutonomyControlCenter() {
    val config = OrionAutonomySettings.read(this)
    val modeLabels = OrionMode.values().map { "${it.name.lowercase().replaceFirstChar(Char::uppercase)} — ${OrionAutonomyPolicy.description(it)}" }
    AlertDialog.Builder(this)
        .setTitle("Orion autonomy control center")
        .setSingleChoiceItems(modeLabels.toTypedArray(), OrionMode.values().indexOf(config.mode)) { d, which ->
            d.dismiss()
            val mode = OrionMode.values()[which]
            val latest = OrionAutonomySettings.read(this).copy(mode = mode)
            OrionAutonomySettings.write(this, latest)
            ToolPermissions.applyLevel(this, OrionAutonomyPolicy.preset(mode))
            // Keep the existing execution governor in sync with the selected control-center limits.
            AgentPolicy.setBudgets(this, latest.maxToolSteps, latest.maxAiRequests, AgentPolicy.maxMinutes(this))
            showControlCenterMenu("Mode saved: ${mode.name}. Existing action-specific confirmations remain in force.")
        }
        .setNeutralButton("More controls") { _, _ -> showControlCenterMenu(null) }
        .setNegativeButton("Close", null)
        .show()
}

private fun Activity.showControlCenterMenu(message: String?) {
    val rows = listOf(
        "Execution limits",
        "Notifications",
        "Individual capability permissions",
        "Android permission status"
    )
    val builder = AlertDialog.Builder(this).setTitle("Autonomy controls")
        .setItems(rows.toTypedArray()) { _, which ->
            when (which) {
                0 -> showExecutionLimits()
                1 -> showAutonomyNotifications()
                2 -> showToolList()
                3 -> showAndroidPermissionStatus()
            }
        }.setNegativeButton("Close", null)
    if (!message.isNullOrBlank()) builder.setMessage(message)
    builder.show()
}

private fun Activity.showExecutionLimits() {
    val current = OrionAutonomySettings.read(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 8, 24, 4)
    }
    fun field(label: String, value: Int, min: Int, max: Int): EditText {
        container.addView(TextView(this).apply { text = "$label ($min–$max)"; setPadding(0, 10, 0, 4) })
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            hint = "$min–$max"
            contentDescription = label
        }.also { container.addView(it, ViewGroup.LayoutParams(-1, -2)) }
    }
    val steps = field("Maximum tool steps per goal", current.maxToolSteps, OrionAutonomyPolicy.MIN_STEPS, OrionAutonomyPolicy.MAX_STEPS)
    val requests = field("Maximum AI requests per goal", current.maxAiRequests, OrionAutonomyPolicy.MIN_REQUESTS, OrionAutonomyPolicy.MAX_REQUESTS)
    val retries = field("Maximum recovery retries (policy cap)", current.maxRetries, OrionAutonomyPolicy.MIN_RETRIES, OrionAutonomyPolicy.MAX_RETRIES)
    AlertDialog.Builder(this).setTitle("Execution limits").setView(container)
        .setPositiveButton("Save") { _, _ ->
            fun value(edit: EditText, fallback: Int) = edit.text.toString().toIntOrNull() ?: fallback
            val updated = OrionAutonomyPolicy.clamp(current.copy(
                maxToolSteps = value(steps, current.maxToolSteps),
                maxAiRequests = value(requests, current.maxAiRequests),
                maxRetries = value(retries, current.maxRetries)
            ))
            OrionAutonomySettings.write(this, updated)
            // AgentPolicy is the enforcement point used by the command loop.
            AgentPolicy.setBudgets(this, updated.maxToolSteps, updated.maxAiRequests, AgentPolicy.maxMinutes(this))
            showControlCenterMenu("Saved. Tool-step and AI-request budgets are now applied to the existing command governor. Recovery retry preference is saved; hard recovery limits still apply.")
        }.setNegativeButton("Cancel", null).show()
}

private fun Activity.showAutonomyNotifications() {
    val current = OrionAutonomySettings.read(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 8, 24, 4)
    }
    fun toggle(label: String, checked: Boolean): Switch =
        Switch(this).apply { text = label; isChecked = checked; panel.addView(this, ViewGroup.LayoutParams(-1, -2)) }
    val completion = toggle("Notify when a goal completes", current.notifyOnCompletion)
    val failure = toggle("Notify when a goal fails or pauses", current.notifyOnFailure)
    val approval = toggle("Notify when approval is needed", current.notifyOnApproval)
    AlertDialog.Builder(this).setTitle("Notification preferences").setView(panel)
        .setPositiveButton("Save") { _, _ ->
            OrionAutonomySettings.write(this, current.copy(
                notifyOnCompletion = completion.isChecked,
                notifyOnFailure = failure.isChecked,
                notifyOnApproval = approval.isChecked
            ))
            showControlCenterMenu("Notification preferences saved.")
        }.setNegativeButton("Cancel", null).show()
}

private fun Activity.showAndroidPermissionStatus() {
    fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val rows = mutableListOf<String>()
    rows += "Microphone: " + if (granted(Manifest.permission.RECORD_AUDIO)) "GRANTED" else "not granted"
    rows += "Contacts: " + if (granted(Manifest.permission.READ_CONTACTS)) "GRANTED" else "not granted"
    val location = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    rows += "Location: " + if (location) "GRANTED" else "not granted"
    if (Build.VERSION.SDK_INT >= 33) rows += "Notifications: " + if (granted(Manifest.permission.POST_NOTIFICATIONS)) "GRANTED" else "not granted / not requested"
    val notificationAccess = runCatching {
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { it.equals("${packageName}/${OrionNotificationListener::class.java.name}", true) }
    }.getOrDefault(false)
    rows += "Notification listener access: " + if (notificationAccess) "GRANTED" else "not granted"
    AlertDialog.Builder(this).setTitle("Android permission status")
        .setMessage(rows.joinToString("\n") + "\n\nOrion reads current grant state. It should request only missing runtime permissions when a feature is enabled.")
        .setPositiveButton("OK", null).show()
}

private fun Activity.showToolList() {
    val tools = ToolRegistry.all.filter { it.name !in ToolPermissionPolicy.alwaysAllowed }
    val rows = tools.map { t -> "${t.name}  [${t.access.name.lowercase()}]  →  ${ToolPermissions.mode(this, t.name).name}" }
    AlertDialog.Builder(this).setTitle("Tap a tool to cycle Allow / Ask / Deny")
        .setItems(rows.toTypedArray()) { _, i -> ToolPermissions.cycle(this, tools[i].name); showToolList() }
        .setNegativeButton("Done", null).show()
}
