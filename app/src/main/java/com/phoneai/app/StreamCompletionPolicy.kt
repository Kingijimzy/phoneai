package com.phoneai.app

/** Rules for deciding whether an SSE response ended cleanly. */
internal object StreamCompletionPolicy {
    fun isComplete(hasVisibleText: Boolean, terminalEventSeen: Boolean, cancelled: Boolean): Boolean =
        !cancelled && (!hasVisibleText || terminalEventSeen)
}
