package com.phoneai.app

import android.app.Application

/** Loads user-added AI endpoints before any screen or service asks for the provider list. */
class OrionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Prefs.loadCustomProviders(this) }
        // Start the background heartbeat whenever the app process starts, not only after the accessibility service connects.
        runCatching { OrionProactiveWork.ensureScheduled(this) }
    }
}
