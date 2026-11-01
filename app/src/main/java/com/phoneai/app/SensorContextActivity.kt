package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Visible, per-scope consent dashboard. Sensor collection is foreground-only in this screen. */
class SensorContextActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var sampler: OrionSensorContextSampler? = null
    private var motionStatus: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0xff090b0e.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SENSOR & CONTEXT PERMISSIONS"
            textSize = 19f; setTextColor(0xfff1f3f5.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Each switch is a separate Orion consent grant, not an Android permission. Android may still ask for its own permission or special access. Grants default to foreground-only. Nothing is collected by this screen unless you enable the relevant scope."
            textSize = 13f; setTextColor(0xffaab2bd.toInt()); setPadding(0, dp(10), 0, dp(16))
        })
        status = TextView(this).apply { textSize = 12f; setTextColor(0xff9da8b5.toInt()) }
        root.addView(status)
        val scopes = listOf(
            OrionSensorScope.MOTION to "Motion sensors",
            OrionSensorScope.DEVICE_STATE to "Device state",
            OrionSensorScope.LOCATION to "Location context",
            OrionSensorScope.SCREEN_CONTEXT to "Screen context",
            OrionSensorScope.MICROPHONE to "Microphone context",
            OrionSensorScope.NOTIFICATIONS to "Notification context",
            OrionSensorScope.APP_USAGE to "App usage context",
            OrionSensorScope.ROUTINE_REMINDERS to "Routine reminders"
        )
        scopes.forEach { (scope, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val text = TextView(this).apply {
                this.text = label
                textSize = 15f; setTextColor(0xffe7ebef.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val toggle = Switch(this).apply {
                isChecked = OrionSensorConsent.read(this@SensorContextActivity, scope).active()
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        OrionSensorConsent.grant(this@SensorContextActivity, scope, 30L * 24 * 60 * 60 * 1000, true)
                        if (scope == OrionSensorScope.MOTION) startMotionSampler()
                    } else {
                        OrionSensorConsent.revoke(this@SensorContextActivity, scope)
                        if (scope == OrionSensorScope.MOTION) {
                            stopMotionSampler()
                            OrionSensorContextStore.clear(this@SensorContextActivity)
                        }
                        if (scope == OrionSensorScope.ROUTINE_REMINDERS) {
                            MemoryStore.all(this@SensorContextActivity).forEach {
                                OrionRoutineReminderWorker.cancel(this@SensorContextActivity, it.id)
                            }
                        }
                    }
                    refreshStatus()
                }
            }
            row.addView(text); row.addView(toggle)
            root.addView(row)
            if (scope == OrionSensorScope.MOTION) {
                motionStatus = TextView(this).apply {
                    text = "Motion observations appear here only while this screen is open."
                    textSize = 12f; setTextColor(0xff8995a3.toInt())
                }
                root.addView(motionStatus)
            }
        }
        root.addView(TextView(this).apply {
            text = "A grant expires after 30 days. Revoke any scope here at any time. Location, notification access, usage access and screen capture still require their separate Android system approvals and are not activated by these switches."
            textSize = 12f; setTextColor(0xff8995a3.toInt()); setPadding(0, dp(16), 0, dp(10))
        })
        val revoke = TextView(this).apply {
            text = "REVOKE ALL ORION CONTEXT GRANTS"
            textSize = 13f; setTextColor(0xffffa5a5.toInt()); setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                AlertDialog.Builder(this@SensorContextActivity)
                    .setTitle("Revoke all context grants?")
                    .setMessage("Orion will stop using all optional sensor and routine-reminder scopes.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("REVOKE") { _, _ ->
                        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(this@SensorContextActivity, it) }
                        MemoryStore.all(this@SensorContextActivity).forEach {
                            OrionRoutineReminderWorker.cancel(this@SensorContextActivity, it.id)
                        }
                        stopMotionSampler()
                        OrionSensorContextStore.clear(this@SensorContextActivity)
                        rebuild()
                    }.show()
            }
        }
        root.addView(revoke)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshStatus()
    }

    private fun rebuild() {
        // Recreate the consent view so every switch reflects the revoked state.
        recreate()
    }

    private fun refreshStatus() {
        val active = OrionSensorConsent.activeScopes(this).map { it.name.lowercase().replace('_', ' ') }
        status.text = if (active.isEmpty()) "No active Orion context grants." else "Active grants: " + active.joinToString(", ")
    }

    private fun startMotionSampler() {
        if (sampler != null) return
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sampler = OrionSensorContextSampler(this, manager) { observation ->
            runOnUiThread { motionStatus?.text = observation }
        }.also {
            if (!it.start()) {
                sampler = null
                Toast.makeText(this, "No motion sensor available or grant inactive.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopMotionSampler() {
        sampler?.stop()
        sampler = null
    }

    override fun onResume() {
        super.onResume()
        if (OrionSensorConsent.read(this, OrionSensorScope.MOTION).active()) startMotionSampler()
    }

    override fun onPause() {
        stopMotionSampler()
        super.onPause()
    }

    override fun onDestroy() {
        stopMotionSampler()
        super.onDestroy()
    }
}
