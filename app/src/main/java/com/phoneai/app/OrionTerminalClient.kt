package com.phoneai.app

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Bridges Termux's maintained VT emulator callbacks into Orion's terminal screen. */
internal class OrionTerminalClient(
    private val view: TerminalView,
    private val onFinished: (TerminalSession) -> Unit
) : TerminalSessionClient, TerminalViewClient {
    override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
    override fun onSessionFinished(finishedSession: TerminalSession) { onFinished(finishedSession) }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { view.onScreenUpdated() }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) { android.util.Log.e("OrionTerminal", "$tag: $message") }
    override fun logWarn(tag: String, message: String) { android.util.Log.w("OrionTerminal", "$tag: $message") }
    override fun logInfo(tag: String, message: String) { android.util.Log.i("OrionTerminal", "$tag: $message") }
    override fun logDebug(tag: String, message: String) { android.util.Log.d("OrionTerminal", "$tag: $message") }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v("OrionTerminal", "$tag: $message") }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e("OrionTerminal", "$tag: $message", e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e("OrionTerminal", tag, e) }

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() { view.onScreenUpdated() }
}
