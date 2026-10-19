package com.phoneai.app

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import java.io.ByteArrayInputStream

/**
 * Optional cinematic 3D stage. The Canvas avatar remains the lightweight/offline fallback.
 * Three.js and the CC0 Quaternius character are fetched from pinned public CDN URLs.
 */
class CinematicAvatarActivity : Activity() {
    companion object {
        @Volatile private var active: CinematicAvatarActivity? = null

        fun pushState(state: String) {
            active?.runScene("setState", state)
        }

        fun pulseMouth() {
            active?.runScene("pulseMouth", "")
        }
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var pageReady = false
    private var latestState = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        window.statusBarColor = Color.rgb(4, 8, 18)
        window.navigationBarColor = Color.rgb(4, 8, 18)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(4, 8, 18))
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(4, 8, 18))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("Orion3D", message.message())
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return blockedResponse()
                return if (host == "cdn.jsdelivr.net" || host == "phoneai.local") null else blockedResponse()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                status.text = "ORION 3D  ·  INITIALIZING"
                runScene("setState", latestState)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text = "3D scene unavailable · check your connection"
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 44, 20, 12)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE6081020.toInt(), 0x00081020)
            )
        }
        val close = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Close 3D avatar"
            setPadding(0, 0, 18, 4)
            setOnClickListener { finish() }
        }
        top.addView(close, LinearLayout.LayoutParams(48, 52))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = "ORION  /  CINEMATIC CORE"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            text = "LOADING 3D CHARACTER…"
            textSize = 11f
            setTextColor(Color.rgb(56, 225, 255))
        }
        labels.addView(status)
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = TextView(this).apply {
            text = "LIVE EXPRESSION  ·  VOICE-SYNCED  ·  CC0 CHARACTER"
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(0xFF9CB6D0.toInt())
            setPadding(12, 14, 12, 28)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6081020.toInt(), 0x00081020)
            )
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        val sceneHtml = assets.open("orion3d/index.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("https://phoneai.local/", sceneHtml, "text/html", "UTF-8", null)
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked by PhoneAI avatar policy",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun runScene(method: String, value: String) {
        latestState = value.ifBlank { latestState }
        if (!pageReady || !::web.isInitialized) return
        val safe = value.replace("\\", "\\\\").replace("'", "\\'")
        web.post {
            web.evaluateJavascript("window.Orion3D && window.Orion3D.$method('$safe')", null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (active === this) active = null
        if (::web.isInitialized) {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.removeAllViews()
            web.destroy()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
        PhoneAiService.instance?.onCinematicClosed()
    }


