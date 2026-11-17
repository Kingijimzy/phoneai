package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Compatibility route retained for old callers. The integrated Studio terminal
 * is the only terminal entry point so it inherits the shared active project.
 */
class TerminalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, StudioActivity::class.java).apply {
            putExtras(intent)
            putExtra("page", "terminal")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(target)
        finish()
    }
}
