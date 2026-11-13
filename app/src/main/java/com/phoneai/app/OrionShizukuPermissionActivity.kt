package com.phoneai.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import rikka.shizuku.Shizuku

/** User-visible permission handoff; Shizuku permission is never requested from a background service. */
class OrionShizukuPermissionActivity : Activity() {
    private val requestCode = 0x4f52
    private val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == requestCode) {
            setResult(if (result == PackageManager.PERMISSION_GRANTED) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            setResult(RESULT_OK)
            finish()
            return
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        super.onDestroy()
    }
}