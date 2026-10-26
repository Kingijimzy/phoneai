package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog

/** Settings screen: pick an autonomy level, or tap individual tools to cycle Allow → Ask → Deny. */
internal fun Activity.showToolPermissions() {
    val levels = ToolPermissionPolicy.levels
    val labels = levels.map { ToolPermissionPolicy.levelLabel(it) } + "Customize individual tools…"
    val current = levels.indexOf(ToolPermissions.level(this)).coerceAtLeast(0)
    AlertDialog.Builder(this).setTitle("Tool permissions & autonomy")
        .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
            d.dismiss()
            if (which < levels.size) {
                ToolPermissions.applyLevel(this, levels[which])
                AlertDialog.Builder(this).setTitle("Saved").setMessage(ToolPermissionPolicy.levelLabel(levels[which])).setPositiveButton("OK", null).show()
            } else showToolList()
        }.setNegativeButton("Close", null).show()
}

private fun Activity.showToolList() {
    val tools = ToolRegistry.all.filter { it.name !in ToolPermissionPolicy.alwaysAllowed }
    val rows = tools.map { t -> "${t.name}  [${t.access.name.lowercase()}]  →  ${ToolPermissions.mode(this, t.name).name}" }
    AlertDialog.Builder(this).setTitle("Tap a tool to cycle Allow / Ask / Deny")
        .setItems(rows.toTypedArray()) { _, i -> ToolPermissions.cycle(this, tools[i].name); showToolList() }
        .setNegativeButton("Done", null).show()
}
