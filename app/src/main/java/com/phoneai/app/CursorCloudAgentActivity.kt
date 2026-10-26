package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cursor Cloud Agents integration. Uses Cursor's documented Cloud Agents API,
 * not the desktop Cursor CLI and not a fabricated browser OAuth callback.
 */
class CursorCloudAgentActivity : Activity() {
    private lateinit var apiKey: EditText
    private lateinit var repoUrl: EditText
    private lateinit var branchName: EditText
    private lateinit var task: EditText
    private lateinit var autoPr: CheckBox
    private lateinit var status: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "CURSOR CLOUD AGENT"
            textSize = 10f
            letterSpacing = .12f
            setTextColor(Theme.MUTED)
        })
        header.addView(TextView(this).apply {
            text = "Use Cursor from Orion"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.TEXT)
            setPadding(0, dp(5), 0, dp(5))
        })
        header.addView(TextView(this).apply {
            text = "This runs a real Cursor cloud coding agent against a connected Git repository. It does not require Cursor CLI or Termux. Cursor's official API uses an API key rather than a third-party OAuth token."
            textSize = 13f
            setTextColor(Theme.MUTED)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { fillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(24))
        }
        body.addView(actionButton("SIGN IN TO CURSOR WEBSITE") {
            openUrl("https://cursor.com/agents")
        })
        body.addView(actionButton("OPEN CURSOR API KEY SETUP") {
            openUrl("https://cursor.com/docs/cloud-agent/api/overview")
        })
        body.addView(TextView(this).apply {
            text = "Create a Cursor API key in your Cursor Dashboard under Integrations/API Keys, then paste it below. The key is stored in Orion's encrypted local credential vault."
            textSize = 12f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(8), 0, dp(12))
        })
        apiKey = editField("Cursor API key", secret = true).apply {
            setText("")
        }
        body.addView(apiKey)
        body.addView(actionButton("SAVE API KEY SECURELY") {
            val key = apiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Paste the Cursor API key first.", Toast.LENGTH_SHORT).show()
            } else if (!Prefs.setKey(this, KEY_ID, key)) {
                Toast.makeText(this, "Could not save the key securely.", Toast.LENGTH_LONG).show()
            } else {
                apiKey.setText("")
                Toast.makeText(this, "Cursor API key saved on this device.", Toast.LENGTH_LONG).show()
                status.text = "API key saved locally. Create a task to verify access."
            }
        })

        body.addView(section("REPOSITORY"))
        repoUrl = editField("Repository URL, e.g. https://github.com/owner/repo").apply {
            setText(sp.getString("cursor_agent_repo", "https://github.com/Kingijimzy/phoneai"))
        }
        body.addView(repoUrl)
        branchName = editField("Starting branch", secret = false).apply {
            setText(sp.getString("cursor_agent_branch", "main"))
        }
        body.addView(branchName)
        body.addView(section("TASK"))
        task = editField("Describe what Cursor should build or fix", secret = false, multiline = true)
        body.addView(task)
        autoPr = CheckBox(this).apply {
            text = "Ask Cursor to open a pull request when finished"
            isChecked = true
            setTextColor(Theme.TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(Theme.TEXT)
        }
        body.addView(autoPr)
        body.addView(actionButton("START CURSOR CLOUD AGENT") { createAgent() })
        body.addView(actionButton("LOAD MY RECENT CURSOR AGENTS") { loadAgents() })
        status = TextView(this).apply {
            text = "Not connected yet."
            textSize = 13f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(12), 0, dp(10))
        }
        body.addView(status)
        body.addView(actionButton("OPEN CURSOR AGENTS") { openUrl("https://cursor.com/agents") })
        body.addView(actionButton("BACK TO ORION") { finish() })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        letterSpacing = .12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.MUTED)
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun editField(hintText: String, secret: Boolean, multiline: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.MUTED)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF171717.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0xFF343434.toInt())
        }
        inputType = if (secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else android.text.InputType.TYPE_CLASS_TEXT or if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = !multiline
        if (multiline) minLines = 4
        layoutParams = LinearLayout.LayoutParams(-1, if (multiline) dp(120) else -2).apply {
            bottomMargin = dp(8)
        }
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Theme.TEXT)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF242424.toInt())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Could not open the browser.", Toast.LENGTH_LONG).show() }
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(task.windowToken, 0)
        } catch (_: Exception) { }
    }

    private fun createAgent() {
        val key = Prefs.key(this, KEY_ID).trim()
        val repo = repoUrl.text.toString().trim()
        val branch = branchName.text.toString().trim().ifBlank { "main" }
        val prompt = task.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Sign in on Cursor, create an API key, and save it here first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!repo.startsWith("https://") || !repo.contains("github.com/") && !repo.contains("gitlab.") && !repo.contains("bitbucket.org/") && !repo.contains("dev.azure.com/")) {
            Toast.makeText(this, "Enter a valid HTTPS repository URL supported by Cursor.", Toast.LENGTH_LONG).show()
            return
        }
        if (prompt.isBlank()) {
            task.error = "Describe the coding task first"
            task.requestFocus()
            return
        }
        getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putString("cursor_agent_repo", repo).putString("cursor_agent_branch", branch).apply()
        hideKeyboard()
        status.text = "Sending one request to Cursor Cloud Agents…"
        val shouldCreatePr = autoPr.isChecked
        setBusy(true)
        Thread {
            var code = -1
            var body = ""
            var error = ""
            try {
                val payload = JSONObject()
                    .put("prompt", JSONObject().put("text", prompt))
                    .put("repos", JSONArray().put(JSONObject().put("url", repo).put("startingRef", branch)))
                    .put("autoCreatePR", shouldCreatePr)
                    .put("workOnCurrentBranch", false)
                    .put("mode", "agent")
                val response = request("POST", "https://api.cursor.com/v1/agents", key, payload.toString())
                code = response.first
                body = response.second
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                setBusy(false)
                if (code in 200..299) {
                    val agentUrl = try {
                        val obj = JSONObject(body)
                        obj.optJSONObject("agent")?.optString("url", "")
                            ?.takeIf { it.startsWith("https://") } ?: "https://cursor.com/agents"
                    } catch (_: Exception) { "https://cursor.com/agents" }
                    status.text = "Cursor accepted the task. The agent is running remotely; Orion did not run this task locally."
                    AlertDialog.Builder(this)
                        .setTitle("Cursor agent started")
                        .setMessage("Cursor accepted your task and queued a cloud run. You can monitor it in Cursor.\\n\\n$agentUrl")
                        .setPositiveButton("OPEN AGENT") { _, _ -> openUrl(agentUrl) }
                        .setNeutralButton("COPY LINK") { _, _ ->
                            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Cursor agent", agentUrl))
                            Toast.makeText(this, "Agent link copied.", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("CLOSE", null).show()
                } else {
                    val detail = if (error.isNotBlank()) error else "HTTP $code: " + body.replace("\\s+".toRegex(), " ").take(350)
                    status.text = "Cursor request failed. Your task was not confirmed as started."
                    AlertDialog.Builder(this).setTitle("Cursor agent not started")
                        .setMessage(detail + "\\n\\nCheck the API key, Cursor plan/API access, and that the repository is connected to Cursor.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun loadAgents() {
        val key = Prefs.key(this, KEY_ID).trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Save your Cursor API key first.", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Loading recent Cursor agents…"
        setBusy(true)
        Thread {
            var code = -1
            var body = ""
            var error = ""
            try {
                val response = request("GET", "https://api.cursor.com/v1/agents?limit=20", key, null)
                code = response.first
                body = response.second
            } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                setBusy(false)
                if (code !in 200..299) {
                    status.text = "Could not load agents."
                    AlertDialog.Builder(this).setTitle("Cursor agents unavailable")
                        .setMessage(if (error.isNotBlank()) error else "HTTP $code: " + body.take(300))
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val items = try {
                    val arr = JSONObject(body).optJSONArray("items") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { index ->
                        val item = arr.optJSONObject(index) ?: return@mapNotNull null
                        val title = item.optString("name", "Cursor agent")
                        val url = item.optString("url", "")
                        if (url.startsWith("https://")) title to url else null
                    }
                } catch (_: Exception) { emptyList<Pair<String, String>>() }
                if (items.isEmpty()) {
                    status.text = "No recent agents returned."
                    Toast.makeText(this, "No Cursor agents found.", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Recent Cursor agents")
                        .setItems(items.map { it.first }.toTypedArray()) { _, which -> openUrl(items[which].second) }
                        .setNegativeButton("CLOSE", null).show()
                    status.text = "Loaded ${items.size} recent agent(s). Tap one to open it."
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        runOnUiThread {
            status.isEnabled = !busy
            status.alpha = if (busy) 0.65f else 1f
        }
    }

    private fun request(method: String, url: String, key: String, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 45000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { conn.disconnect() }
    }

    companion object {
        private const val KEY_ID = "cursor_cloud_agents"
    }
}
