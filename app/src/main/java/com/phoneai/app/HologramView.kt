package com.phoneai.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The human-head hologram (the page bundled with Orion in assets/orion3d), shown inside the chat.
 * It is a local page: no network, no file access beyond the bundled asset, no navigation, no bridge to the app.
 * The app only pushes a state, a status line and a caption into it.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class HologramView(context: Context) : WebView(context) {
    private var ready = false
    private val queued = ArrayList<String>()

    init {
        setBackgroundColor(Color.BLACK)
        with(settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { ready = true; queued.forEach { evaluateJavascript(it, null) }; queued.clear() }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
        }
        isHorizontalScrollBarEnabled = false; isVerticalScrollBarEnabled = false
        loadUrl("file:///android_asset/orion3d/index.html")
    }

    private fun js(code: String) { if (ready) evaluateJavascript(code, null) else queued.add(code) }

    /** state is one of idle, listening, thinking, speaking, acting. */
    fun show(state: String, status: String, caption: String) {
        js("(function(){if(window.Orion3D){Orion3D.setState(${JSONObject.quote(state)},${JSONObject.quote(caption)});" +
            "var s=document.getElementById('status');if(s)s.textContent=${JSONObject.quote(status.uppercase())};}})()")
    }

    fun pulse(v: Float) = js("window.Orion3D&&Orion3D.pulse($v)")
    fun mic(level: Float) = js("window.Orion3D&&Orion3D.setMicLevel($level)")

    fun stateName(a: CompanionView.Activity): String = when (a) {
        CompanionView.Activity.IDLE -> "idle"
        CompanionView.Activity.THINKING -> "thinking"
        CompanionView.Activity.LISTENING -> "listening"
        CompanionView.Activity.SPEAKING -> "speaking"
    }
}
