package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Legacy workspace route retained as a redirect to the single Studio host. */
class WorkspaceShellActivity : Activity() {
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
