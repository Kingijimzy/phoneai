package com.phoneai.app

import android.app.Application

/** Loads user-added AI endpoints before any screen or service asks for the provider list. */
class OrionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Prefs.loadCustomProviders(this) }
        // Initialize Shizuku binder listeners early; the optional UserService binds only after permission.
        runCatching { OrionShizukuBridge.initialize(this) }
        // Re-register persisted reminders after process recreation; boot recovery remains in the receiver.
        runCatching { TaskReminder.restoreAll(this) }
        // Start the background heartbeat whenever the app process starts, not only after the accessibility service connects.
        if (OrionMasterControl.isEnabled(this)) {
            runCatching { OrionProactiveWork.ensureScheduled(this) }
            runCatching { OrionDeviceHealthWork.ensureScheduled(this) }
            runCatching { OrionRuntime(this).pendingItems().takeIf { it.isNotEmpty() }?.let { OrionRecoveryWork.schedule(this) } }
        }
        // The managed website API server is a child of Orion's PRoot runtime. Reattach it
        // after Android recreates the app process; never install packages during app startup.
        runCatching { ApiAnythingManager.resumeIfEnabled(this) }
    }
}
