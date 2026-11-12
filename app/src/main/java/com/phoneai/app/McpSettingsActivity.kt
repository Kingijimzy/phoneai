package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Dedicated remote MCP endpoint, pairing and least-privilege scope controls. */
class McpSettingsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var root: LinearLayout
    private lateinit var apiAnythingStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun panel() = GradientDrawable().apply {
        setColor(Theme.PANEL)
        cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
        setStroke(dp(1), Theme.LINE)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        minHeight = dp(48)
        isFocusable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun card(title: String, body: LinearLayout.() -> Unit) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(16), dp(15), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            body()
        }
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        section.alpha = 0f
        section.translationY = dp(6).toFloat()
        section.post {
            section.animate().alpha(1f).translationY(0f).setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    private fun field(hintText: String, value: String = "", secret: Boolean = false, numeric: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            background = GradientDrawable().apply {
                setColor(Theme.PANEL2)
                cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                setStroke(dp(1), Theme.LINE_STRONG)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(50)
            setSingleLine(true)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(20), dp(17), dp(28))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  INTEGRATIONS", 10f, Theme.SILVER, true))
        root.addView(label("Remote MCP", 27f, Theme.TEXT, true).apply { setPadding(0, dp(7), 0, dp(5)) })
        root.addView(label(
            "Connect a remote Model Context Protocol server. Orion enforces local scopes and asks before each consequential remote tool call. Use only servers you trust.",
            13f, Theme.MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })

        card("API ANYTHING  /  WEBSITE CAPABILITY ENGINE") {
            addView(label(
                "Managed locally inside Orion's Alpine runtime. It can discover existing site operations, learn new website reads, reuse saved operations and repair drifted operations. No website calls are routed through a third-party proxy.",
                12f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            apiAnythingStatus = label(ApiAnythingManager.status(this@McpSettingsActivity), 12f, Theme.SILVER)
            addView(apiAnythingStatus)
            addView(button("Install / start / repair API Anything", ::startApiAnything),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Stop managed API Anything", ::stopApiAnything),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        card("ENDPOINT") {
            endpoint = label("", 12f, Theme.SILVER).apply { setPadding(0, dp(8), 0, dp(4)) }
            status = label("", 12f, Theme.MUTED)
            addView(endpoint)
            addView(status)
            addView(button("Configure remote MCP server", ::configureEndpoint),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Discover MCP tools", ::discoverTools),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        card("PAIRING & TRUST") {
            addView(label(
                "Pairing is a local confirmation step, not proof of remote server identity. HTTPS and a trusted endpoint are still required.",
                12f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(button(if (McpConnector.isPaired(this@McpSettingsActivity)) "Re-pair MCP endpoint with PIN" else "Pair MCP endpoint with PIN", ::pairEndpoint),
                LinearLayout.LayoutParams(-1, -2))
            addView(button("MCP connection status", ::showStatus),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        card("PERMISSIONS & SCOPES") {
            val scopes = McpConnector.scopes(this@McpSettingsActivity)
            addView(label("Read / inspect: always enabled", 12f, Theme.SILVER).apply { setPadding(0, dp(8), 0, dp(4)) })
            addView(label(
                listOf("write", "communication", "system", "other")
                    .filter { it in scopes }.joinToString(" · ").ifBlank { "No additional scopes granted" },
                12f, Theme.MUTED
            ).apply { setPadding(0, 0, 0, dp(8)) })
            addView(button("MCP permissions and scopes", ::editScopes),
                LinearLayout.LayoutParams(-1, -2))
            addView(button("Revoke remote access and clear credentials", ::revokeAccess),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        root.addView(button("Back to settings") { finish() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(root)
        })
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val url = McpConnector.endpoint(this)
        endpoint.text = if (url.isBlank()) "No endpoint configured" else url
        status.text = McpConnector.status(this)
        if (::apiAnythingStatus.isInitialized) apiAnythingStatus.text = ApiAnythingManager.status(this)
    }

    private fun startApiAnything() {
        val progress = ProgressDialog.show(this, "Preparing API Anything", "Installing and connecting the local website capability engine…", true, false)
        worker.execute {
            val result = ApiAnythingManager.installAndStart(applicationContext) { message ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::apiAnythingStatus.isInitialized) {
                        apiAnythingStatus.text = message
                    }
                }
            }
            runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                showInfo("API Anything", result.getOrElse { "Setup failed: " + (it.message ?: it.javaClass.simpleName) })
                refresh()
            }
        }
    }

    private fun stopApiAnything() {
        AlertDialog.Builder(this)
            .setTitle("Stop API Anything?")
            .setMessage("This stops the local website-operation server and disconnects its managed MCP endpoint. Learned site operations remain stored in Orion's Linux runtime.")
            .setPositiveButton("Stop server") { _, _ ->
                ApiAnythingManager.stop(applicationContext)
                refresh()
                showInfo("API Anything", "Managed API Anything server stopped. Learned operations were preserved.")
            }
            .setNegativeButton("Keep running", null)
            .show()
    }

    private fun configureEndpoint() {
        val endpointInput = field("MCP endpoint URL (HTTPS)", McpConnector.endpoint(this))
        val tokenInput = field("Optional bearer token (encrypted)", secret = true)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(
                "Use HTTPS; HTTP is allowed only for localhost. The bearer token is stored with Android Keystore and is never inserted into the model prompt.",
                12f, Theme.MUTED
            ))
            addView(endpointInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(tokenInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("Remote MCP setup")
            .setView(wrap)
            .setPositiveButton("Save server") { _, _ ->
                val result = McpConnector.save(this, endpointInput.text.toString(), tokenInput.text.toString())
                showInfo("MCP setup", result)
                refresh()
            }
            .setNeutralButton("Disconnect") { _, _ ->
                McpConnector.clear(this)
                Toast.makeText(this, "MCP server settings cleared.", Toast.LENGTH_LONG).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun discoverTools() {
        val progress = ProgressDialog.show(this, "Discovering MCP tools", "Contacting the configured endpoint…", true, false)
        worker.execute {
            val result = runCatching { McpConnector.listTools(applicationContext) }
            runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                showInfo("MCP tools", result.getOrElse { "Discovery failed: " + (it.message ?: "unknown error") })
                refresh()
            }
        }
    }

    private fun pairEndpoint() {
        val pin = McpConnector.beginPairing(this)
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            showInfo("MCP pairing", pin)
            refresh()
            return
        }
        val code = field("Enter the six-digit code", numeric = true)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(
                "LOCAL CONFIRMATION CODE\n$pin\n\nEnter this same code below within two minutes to confirm you intentionally trust this configured endpoint on this device.",
                14f, Theme.TEXT, true
            ).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(14)) })
            addView(code)
        }
        AlertDialog.Builder(this)
            .setTitle("Approve MCP endpoint")
            .setMessage("This does not prove the remote server's identity. Use HTTPS and only connect to a server you trust.")
            .setView(wrap)
            .setPositiveButton("Confirm pairing") { _, _ ->
                showInfo("MCP pairing", McpConnector.confirmPairing(this, code.text.toString()))
                refresh()
            }
            .setNeutralButton("Revoke pairing") { _, _ ->
                McpConnector.unpair(this)
                showInfo("MCP pairing", "Local endpoint approval revoked.")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editScopes() {
        val selected = McpConnector.scopes(this).toMutableSet()
        val options = listOf(
            "read" to "Read / search / inspect (always enabled)",
            "write" to "Create / edit / delete / execute",
            "communication" to "Send messages / email / publish",
            "system" to "System / device / settings tools",
            "other" to "Unclassified tools (use cautiously)"
        )
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        options.forEach { (scope, title) ->
            wrap.addView(CheckBox(this).apply {
                text = title
                setTextColor(Theme.TEXT)
                buttonTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
                isChecked = scope in selected
                isEnabled = scope != "read"
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(scope) else selected.remove(scope)
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("MCP permissions")
            .setMessage("Read-only stays enabled. Choose the categories Orion may call. Each consequential remote tool call still requires separate approval.")
            .setView(wrap)
            .setPositiveButton("Save scopes") { _, _ ->
                showInfo("MCP permissions", McpConnector.setScopes(this, selected))
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun revokeAccess() {
        AlertDialog.Builder(this)
            .setTitle("Revoke remote MCP access?")
            .setMessage("This clears the saved endpoint, bearer token, pairing approval and extra scopes.")
            .setPositiveButton("Revoke access") { _, _ ->
                McpConnector.clear(this)
                showInfo("MCP access revoked", "The endpoint, token, pairing and scopes have been cleared.")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatus() = showInfo("MCP connection", McpConnector.status(this))

    private fun showInfo(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
