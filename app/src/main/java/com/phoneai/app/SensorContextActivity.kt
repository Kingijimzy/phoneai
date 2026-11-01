package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
                    if (scope == OrionSensorScope.NOTIFICATIONS) OrionNotificationListener.refreshConsent()
                    refreshStatus()
                }
            }
            row.addView(text); row.addView(toggle)
            root.addView(row)
            root.addView(TextView(this).apply {
                text = scopeDescription(scope)
                textSize = 11f; setTextColor(0xff8995a3.toInt())
                setPadding(0, 0, 0, dp(5))
            })
            if (scope == OrionSensorScope.MOTION) {
                motionStatus = TextView(this).apply {
                    text = "Motion observations appear here only while this screen is open."
                    textSize = 12f; setTextColor(0xff8995a3.toInt())
                }
                root.addView(motionStatus)
            }
        }
        root.addView(TextView(this).apply {
            text = "APP USAGE REQUIRES ANDROID SPECIAL ACCESS"
            textSize = 12f; setTextColor(0xffc4ccd5.toInt()); setPadding(0, dp(14), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "OPEN ANDROID USAGE ACCESS SETTINGS"
            textSize = 13f; setTextColor(0xffd5e3f2.toInt()); setPadding(0, dp(8), 0, dp(12))
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    .onFailure { Toast.makeText(this@SensorContextActivity, "Android Usage Access settings are unavailable.", Toast.LENGTH_SHORT).show() }
            }
        })
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


    private fun scopeDescription(scope: OrionSensorScope): String = when (scope) {
        OrionSensorScope.MOTION -> "Foreground collector: coarse accelerometer/gyroscope hints while this screen is open."
        OrionSensorScope.DEVICE_STATE -> "On-demand battery, charging, power-saver, network type and thermal snapshot; not stored."
        OrionSensorScope.LOCATION -> "On-demand last-known approximate location, only with Android location permission; no background tracking."
        OrionSensorScope.SCREEN_CONTEXT -> "Manual screen reads only; no continuous screenshot or screen recording."
        OrionSensorScope.MICROPHONE -> "Voice sessions only after you explicitly start them; no background listening."
        OrionSensorScope.NOTIFICATIONS -> "Safe summaries are kept in memory only when this grant and Android Notification access are both enabled."
        OrionSensorScope.APP_USAGE -> "On-demand recent app-use summary, only with Android Usage Access; sensitive apps are excluded."
        OrionSensorScope.ROUTINE_REMINDERS -> "Allows only one-time reminders you explicitly schedule for approved routines."
    }

    private fun rebuild() {
        // Recreate the consent view so every switch reflects the revoked state.
        recreate()
    }

    private fun refreshStatus() {
        val active = OrionSensorConsent.activeScopes(this).map { it.name.lowercase().replace('_', ' ') }
        status.text = if (active.isEmpty()) "No active Orion context grants." else "Granted scopes (not all have active collectors): " + active.joinToString(", ")
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
