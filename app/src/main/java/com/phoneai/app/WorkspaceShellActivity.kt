package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Legacy workspace entry point retained for existing navigation routes. */
class WorkspaceShellActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, CodeStudioActivity::class.java))
        finish()
    }
}