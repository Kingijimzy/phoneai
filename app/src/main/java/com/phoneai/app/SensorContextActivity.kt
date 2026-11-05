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
    private val scopeSwitches = mutableMapOf<OrionSensorScope, Switch>()

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
            text = "Each switch is a separate Orion consent grant, not an Android permission. Android may still ask for its own permission or special access. Most grants default to foreground-only; Chronos requires a separate explicit background grant. Nothing is collected unless you enable the relevant scope."
            textSize = 13f; setTextColor(0xffaab2bd.toInt()); setPadding(0, dp(10), 0, dp(16))
        })
        root.addView(TextView(this).apply {
            text = "When enabled, relevant context can be included in Orion requests to your selected AI provider. Motion summaries expire after 15 minutes; device, location and app-usage snapshots are not stored; safe notification summaries stay in memory only. Revoke a grant to stop its context from being used."
            textSize = 12f; setTextColor(0xffc0c8d2.toInt()); setPadding(0, 0, 0, dp(12))
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
            OrionSensorScope.CHRONOS_AWARENESS to "Chronos background app-category awareness",
            OrionSensorScope.ROUTINE_REMINDERS to "Routine reminders",
            OrionSensorScope.CONTACTS to "Contacts",
            OrionSensorScope.NETWORK to "Python network access"
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
                        if (scope == OrionSensorScope.CHRONOS_AWARENESS &&
                            !OrionSensorConsent.read(this@SensorContextActivity, OrionSensorScope.APP_USAGE).active()) {
                            toggle.isChecked = false
                            Toast.makeText(this@SensorContextActivity, "Enable App usage context first.", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                        OrionSensorConsent.grant(
                            this@SensorContextActivity, scope, 30L * 24 * 60 * 60 * 1000,
                            foregroundOnly = scope != OrionSensorScope.CHRONOS_AWARENESS
                        )
                        if (scope == OrionSensorScope.MOTION) startMotionSampler()
                    } else {
                        OrionSensorConsent.revoke(this@SensorContextActivity, scope)
                        if (scope == OrionSensorScope.CHRONOS_AWARENESS || scope == OrionSensorScope.APP_USAGE) {
                            OrionChronosAwareness.clear(this@SensorContextActivity)
                        }
                        if (scope == OrionSensorScope.APP_USAGE) {
                            OrionSensorConsent.revoke(this@SensorContextActivity, OrionSensorScope.CHRONOS_AWARENESS)
                            scopeSwitches[OrionSensorScope.CHRONOS_AWARENESS]?.isChecked = false
                        }
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
            scopeSwitches[scope] = toggle
            row.addView(text); row.addView(toggle)
            root.addView(row)
            root.addView(TextView(this).apply {
                this.text = scopeDescription(scope)
                textSize = 11f; setTextColor(0xff8995a3.toInt())
                setPadding(0, 0, 0, dp(5))
            })
            if (scope == OrionSensorScope.MOTION) {
                motionStatus = TextView(this).apply {
                    this.text = "Motion observations appear here only while this screen is open."
                    textSize = 12f; setTextColor(0xff8995a3.toInt())
                }
                root.addView(motionStatus)
            }
        }
        root.addView(TextView(this).apply {
            text = "PYTHON NETWORK HOSTS"
            textSize = 12f; setTextColor(0xffc4ccd5.toInt()); setPadding(0, dp(14), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "MANAGE APPROVED PYTHON HOSTS"
            textSize = 13f; setTextColor(0xffd5e3f2.toInt()); setPadding(0, dp(8), 0, dp(12))
            setOnClickListener { managePythonHosts() }
        })
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
                        OrionChronosAwareness.clear(this@SensorContextActivity)
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
        OrionSensorScope.CHRONOS_AWARENESS -> "Separate background grant. Uses Accessibility window-change events to keep only a coarse app category and focus duration locally for up to two hours. No package name, screen text, notifications or sensitive-app context is retained. Requires App usage context and an enabled Orion Accessibility service."
        OrionSensorScope.ROUTINE_REMINDERS -> "Allows only one-time reminders you explicitly schedule for approved routines."
        OrionSensorScope.CONTACTS -> "Allows bounded local contact searches after both this grant and Android Contacts permission are enabled."
        OrionSensorScope.NETWORK -> "Allows HTTPS/HTTP and persistent WSS/WS connections only to exact hosts you explicitly add below. RFC1918 private LAN hosts (10/8, 172.16/12, 192.168/16) are allowed after exact-host approval; HTTP and ws:// require the local plain-HTTP switch. POST and WebSocket sends ask each time unless you grant writes for that host."
    }

    private fun managePythonHosts() {
        val hosts = OrionPythonNetworkPolicy.grantedHosts(this)
        AlertDialog.Builder(this)
            .setTitle("Approved Python hosts")
            .setItems(hosts.toTypedArray()) { _, which ->
                val host = hosts[which]
                AlertDialog.Builder(this)
                    .setMessage("Revoke Python network access to $host?")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("REVOKE") { _, _ ->
                        OrionPythonNetworkPolicy.revokeHost(this, host)
                        Toast.makeText(this, "Revoked $host", Toast.LENGTH_SHORT).show()
                    }.show()
            }
            .setPositiveButton("ADD HOST") { _, _ -> addPythonHost() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun addPythonHost() {
        val host = android.widget.EditText(this).apply {
            hint = "api.example.com or localhost"
            setSingleLine(true)
        }
        val allowHttp = android.widget.CheckBox(this).apply { text = "Allow plain HTTP (local endpoints only)" }
        val allowWrites = android.widget.CheckBox(this).apply { text = "Allow HTTP POST and WebSocket sends without asking each time" }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(host); addView(allowHttp); addView(allowWrites)
        }
        AlertDialog.Builder(this)
            .setTitle("Grant Python host access")
            .setMessage("Only add services you trust. HTTPS is required for remote hosts.")
            .setView(panel)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                val ok = OrionPythonNetworkPolicy.grantHost(
                    this, host.text.toString(), allowHttp.isChecked, allowWrites.isChecked
                )
                Toast.makeText(
                    this,
                    if (ok) "Host grant saved. Enable Python network access above to use it."
                    else "Invalid host name.",
                    Toast.LENGTH_LONG
                ).show()
            }.show()
    }

    private fun rebuild() {
        // Recreate the consent view so every switch reflects the revoked state.
        recreate()
    }

    private fun refreshStatus() {
        if (!OrionSensorConsent.read(this, OrionSensorScope.APP_USAGE).active()) {
            OrionSensorConsent.revoke(this, OrionSensorScope.CHRONOS_AWARENESS)
            OrionChronosAwareness.clear(this)
            scopeSwitches[OrionSensorScope.CHRONOS_AWARENESS]?.isChecked = false
        }
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
