package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Compatibility route for older navigation intents. StudioActivity is the sole
 * owner of Code Studio UI, project selection, editor and terminal.
 */
class CodeStudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, StudioActivity::class.java).apply {
            putExtras(intent)
            if (!hasExtra("page")) putExtra("page", "editor")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(target)
        finish()
    }
}
