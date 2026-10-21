package com.phoneai.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import java.net.URLEncoder

/** Orion's own browser. Orion can open pages here and read them aloud or summarise them. */
class BrowserActivity : Activity() {

    companion object {
        @Volatile
        var current: BrowserActivity? = null
    }

    private lateinit var web: WebView
    private lateinit var bar: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fix(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(" ") && t.contains(".")) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.BG)
        root.setPadding(0, dp(28), 0, 0)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(4), dp(8), dp(4))
        bar = EditText(this)
        bar.setSingleLine(true)
        bar.hint = "Search or type a web address"
        bar.setTextColor(Theme.TEXT)
        bar.setHintTextColor(Theme.MUTED)
        bar.textSize = 14f
        bar.inputType = InputType.TYPE_TEXT_VARIATION_URI
        bar.imeOptions = EditorInfo.IME_ACTION_GO
        bar.setOnEditorActionListener { _, _, _ ->
            web.loadUrl(fix(bar.text.toString()))
            true
        }
        top.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val back = Button(this)
        back.text = "<"
        back.setOnClickListener { if (web.canGoBack()) web.goBack() }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(44)))
        val close = Button(this)
        close.text = "X"
        close.setOnClickListener { finish() }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && !bar.hasFocus()) bar.setText(url)
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        web.loadUrl(fix(intent.getStringExtra("url") ?: "https://www.google.com"))
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        val u = intent?.getStringExtra("url")
        if (u != null) web.loadUrl(fix(u))
    }

    /** Gives the visible text of the page. Call from any thread; cb runs on the main thread. */
    fun pageText(cb: (String) -> Unit) {
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { v ->
            val text = try {
                JSONArray("[" + (v ?: "\"\"") + "]").optString(0)
            } catch (e: Exception) {
                ""
            }
            cb(text)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (current === this) current = null
        web.destroy()
        super.onDestroy()
    }
}
