package com.phoneai.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import java.net.URLEncoder

/**
 * Lightweight in-app browser. The accessibility agent can inspect and interact with
 * its visible WebView just like other accessible apps. Web content is always untrusted.
 */
class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pill(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Theme.CYAN)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(10), dp(11), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), Theme.LINE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF02080A.toInt(), 0xFF071311.toInt(), 0xFF0A1715.toInt()))
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(OrionIconView(this).apply {
            icon = "browser"
            tint = Theme.CYAN
            contentDescription = "Orion Browser"
        }, LinearLayout.LayoutParams(dp(23), dp(23)).apply { rightMargin = dp(8) })
        title = TextView(this).apply {
            text = "ORION BROWSER"
            textSize = 13f
            setTextColor(Theme.CYAN)
            typeface = Typeface.DEFAULT_BOLD
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(pill("Done") { finish() })
        root.addView(heading)

        address = EditText(this).apply {
            hint = "Search or enter website"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(Theme.PANEL2)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    navigate(address.text.toString()); true
                } else false
            }
        }
        root.addView(address, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.CYAN)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Theme.LINE)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(pill("‹ Back") { if (web.canGoBack()) web.goBack() })
        controls.addView(pill("Forward ›") { if (web.canGoForward()) web.goForward() })
        controls.addView(pill("↻ Reload") { web.reload() })
        controls.addView(pill("⌂ Search") { navigate("https://duckduckgo.com/") })
        root.addView(controls)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return uri.scheme != "https" && uri.scheme != "http"
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank()) address.setText(url)
                title.text = "ORION BROWSER · LOADING"
                progress.visibility = View.VISIBLE
                progress.progress = 5
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) address.setText(url)
                title.text = "ORION BROWSER · READY"
                progress.progress = 100
                progress.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
            override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                if (!pageTitle.isNullOrBlank()) title.text = pageTitle.take(42)
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val initial = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { "https://duckduckgo.com/" }
        navigate(initial)
    }

    private fun navigate(input: String) {
        val raw = input.trim()
        if (raw.isBlank()) return
        val candidate = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http", true))) {
            "https://duckduckgo.com/?q=" + URLEncoder.encode(raw, "UTF-8")
        } else if (raw.startsWith("https://", true) || raw.startsWith("http://", true)) {
            raw
        } else {
            "https://$raw"
        }
        val uri = try { Uri.parse(candidate) } catch (_: Exception) { null }
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Enter a valid website or search query.", Toast.LENGTH_SHORT).show()
            return
        }
        address.setText(candidate)
        web.loadUrl(candidate)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.phoneai.app.browser.URL"
    }
}
