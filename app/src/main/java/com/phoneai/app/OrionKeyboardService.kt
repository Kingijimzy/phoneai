package com.phoneai.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Orion's privacy-first keyboard.
 *
 * Privacy invariant: this IME only sends explicit key actions to the focused editor. It never
 * calls getTextBeforeCursor/getTextAfterCursor/getSelectedText/getExtractedText, never observes
 * typed content, and never sends keystrokes to Orion's AI providers.
 *
 * Agent text entry prefers AccessibilityNodeInfo.ACTION_SET_TEXT, which needs no keyboard switch,
 * no system picker and no wait. The IME path remains as a fallback for fields that reject
 * set-text, and it only runs when Orion's keyboard is genuinely selected and enabled.
 */
class OrionKeyboardService : InputMethodService() {
    companion object {
        @Volatile private var active: OrionKeyboardService? = null

        /**
         * Enters agent-provided text into the focused field.
         *
         * Route 1 (preferred): accessibility ACTION_SET_TEXT — deterministic, instant, verifiable
         * by reading the field back. Route 2: Orion's own InputConnection, used only when Orion is
         * already the active, enabled IME. The old behaviour opened Android's keyboard picker and
         * blocked for 25 seconds; when the owner never tapped the picker that produced the
         * misleading "Orion Private Keyboard was not selected" failure on every attempt.
         */
        fun typeIntoFocusedField(context: android.content.Context, text: String, targetPackage: String?): String {
            OrionKeyboardPolicy.validateRequest(text)?.let { return it }

            accessibilitySetText(text, targetPackage)?.let { return it }

            val service = active
            val ourIme = android.content.ComponentName(context, OrionKeyboardService::class.java)
                .flattenToString()
            val selectedIme = KeyboardStatus.selectedIme(context)
            val orionEnabled = KeyboardStatus.usable(ourIme, KeyboardStatus.enabledImes(context))

            // Do not trust a stale InputConnection retained after Android switches to another IME.
            if (service != null && orionEnabled && selectedIme == ourIme && service.currentInputConnection != null) {
                return service.commitAgentText(text, targetPackage)
            }
            if (!orionEnabled) {
                return "error: this field rejected accessibility text entry and Orion Private Keyboard is not enabled in Android. Enable it once in Settings > System > Languages & input > On-screen keyboard, then retry. Nothing was typed."
            }
            if (selectedIme != ourIme) {
                runCatching {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showInputMethodPicker()
                }
                return "error: this field rejected accessibility text entry and Orion Private Keyboard is enabled but not active. The keyboard picker was opened - select Orion Private Keyboard, then retry. Nothing was typed."
            }
            return "error: Orion Private Keyboard is active but Android has no input connection to the focused field. Tap the field and retry. Nothing was typed."
        }

        /**
         * Writes through the accessibility node, the same primitive device automation uses.
         * Returns null when the field cannot be located or refuses the action, so the caller can
         * fall back to the privacy keyboard instead of reporting a false success.
         */
        private fun accessibilitySetText(text: String, targetPackage: String?): String? {
            val service = PhoneAiService.instance ?: return null
            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
            val field = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                ?: findEditable(root)
                ?: return null
            if (field.isPassword) return "error: refusing to type into a password field"
            val fieldPackage = field.packageName?.toString()
            if (!targetPackage.isNullOrBlank() && !fieldPackage.isNullOrBlank() && fieldPackage != targetPackage) {
                return "error: focused editor changed before text was entered; the text was not typed"
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var accepted = runCatching {
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!accepted) {
                runCatching {
                    if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                runCatching { field.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                runCatching { Thread.sleep(60L) }
                accepted = runCatching {
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }.getOrDefault(false)
            }
            if (!accepted) return null
            runCatching { field.refresh() }
            val current = runCatching { field.text?.toString() }.getOrNull()
            return when {
                current == null ->
                    "typed via accessibility ACTION_SET_TEXT; the field does not expose its text, so the write is unverified"
                current == text ->
                    "typed via accessibility ACTION_SET_TEXT; verified by reading the focused field back"
                else ->
                    "error: the field accepted ACTION_SET_TEXT but its content does not match the requested text, so the write is unverified"
            }
        }

        private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findEditable(child)
                if (found != null) return found
            }
            return null
        }

        private fun isPasswordInput(info: EditorInfo?): Boolean {
            val type = info?.inputType ?: return false
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            return (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }
    // Keep Orion selected across a burst of agent typing. Restore the user's IME only after
    // the typing stream has been idle, rather than switching keyboards after every type call.
    private val imeRestoreHandler = Handler(Looper.getMainLooper())
    private val restorePreviousIme = Runnable {
        val selectedIme = KeyboardStatus.selectedIme(this)
        val ourIme = android.content.ComponentName(this, OrionKeyboardService::class.java)
            .flattenToShortString()
        if (OrionKeyboardPolicy.shouldRestorePreviousIme(selectedIme, ourIme)) {
            runCatching { switchToPreviousInputMethod() }
        }
    }
    private val imeRestoreIdleMs = 2_000L

    private var upper = true
    private var symbols = false
    private var inputClass = InputType.TYPE_CLASS_TEXT
    private var inputFlags = 0

    private val bg = Color.rgb(13, 17, 20)
    private val keyBg = Color.rgb(31, 39, 43)
    private val accent = Color.rgb(85, 224, 207)
    private val textColor = Color.rgb(239, 246, 247)

    private val numericMode: Boolean
        get() = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_DATETIME

    private val phoneMode: Boolean
        get() = inputClass == InputType.TYPE_CLASS_PHONE

    override fun onCreate() {
        super.onCreate()
        active = this
    }

    override fun onDestroy() {
        imeRestoreHandler.removeCallbacks(restorePreviousIme)
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val type = attribute?.inputType ?: InputType.TYPE_CLASS_TEXT
        inputClass = type and InputType.TYPE_MASK_CLASS
        inputFlags = type and InputType.TYPE_MASK_FLAGS
        upper = (type and (
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )) != 0
        symbols = false
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        active = this
        imeRestoreHandler.removeCallbacks(restorePreviousIme)
        setInputView(buildKeyboard())
    }

    private fun commitAgentText(text: String, targetPackage: String?): String {
        val info = currentInputEditorInfo
            ?: return "error: no active editor connection; focus the text field and retry"
        val connection = currentInputConnection
        OrionKeyboardPolicy.validateFocusedEditor(
            targetPackage = targetPackage,
            focusedPackage = info.packageName,
            isPassword = isPasswordInput(info),
            hasConnection = connection != null
        )?.let { return it }
        val activeConnection = connection
            ?: return "error: Android has not provided an input connection for the focused field"
        // Select the existing field contents first so agent text replaces rather than appends.
        // If the editor does not support select-all, fail safely instead of duplicating text.
        val selectedAll = try { activeConnection.performContextMenuAction(android.R.id.selectAll) } catch (_: Exception) { false }
        if (!OrionKeyboardPolicy.canReplaceField(selectedAll)) {
            return "error: this field did not support select-all, so Orion did not type to avoid appending duplicate text. Select the field contents manually and retry."
        }
        val accepted = activeConnection.commitText(text, 1)
        if (!accepted) return "error: the focused app rejected replacement text from the active input connection"
        // Keep Orion active for a burst of adjacent field updates. Every successful write
        // resets the idle timer; restore only after the burst ends, and only if the user
        // has not manually selected a different IME in the meantime.
        imeRestoreHandler.removeCallbacks(restorePreviousIme)
        imeRestoreHandler.postDelayed(restorePreviousIme, imeRestoreIdleMs)
        return "typed via Orion Private Keyboard input connection; previous keyboard restoration scheduled after typing becomes idle"
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun buildKeyboard(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(7), dp(5), dp(8))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(key("ORION · PRIVATE", accent, 12f) {}, LinearLayout.LayoutParams(0, dp(32), 1f))
        header.addView(key("⌄", keyBg, 18f) { requestHideSelf(0) }, LinearLayout.LayoutParams(dp(42), dp(32)))
        root.addView(header)

        val rows = when {
            numericMode -> numericRows()
            phoneMode -> listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("+", "0", "⌫"),
                listOf("ABC", "↵")
            )
            symbols -> listOf(
                listOf("1","2","3","4","5","6","7","8","9","0"),
                listOf("@","#","$","%","&","-","+","(",")","/"),
                listOf("<", ">", "*", "\\", "'", ":", ";", "!", "?", "⌫"),
                listOf("ABC",",","space",".","↵")
            )
            else -> listOf(
                (if (upper) "QWERTYUIOP" else "qwertyuiop").map { it.toString() },
                (if (upper) "ASDFGHJKL" else "asdfghjkl").map { it.toString() },
                listOf("⇧") + (if (upper) "ZXCVBNM" else "zxcvbnm").map { it.toString() } + listOf("⌫"),
                listOf("?123",",","space",".","↵")
            )
        }

        rows.forEach { labels ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val rowLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(49))
            rowLp.topMargin = dp(4)
            labels.forEach { label ->
                val weight = when (label) {
                    "space" -> 4.5f
                    "⇧", "⌫", "?123", "ABC", "↵" -> 1.45f
                    else -> 1f
                }
                val keyView = key(label, if (label == "↵") accent else keyBg,
                    if (label == "space") 12f else 16f) { handleKey(label) }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                lp.marginStart = dp(2)
                lp.marginEnd = dp(2)
                row.addView(keyView, lp)
            }
            root.addView(row, rowLp)
        }
        return root
    }

    private fun numericRows(): List<List<String>> {
        val rows = mutableListOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        )
        val bottom = mutableListOf<String>()
        if (inputFlags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) bottom.add("−")
        if (inputClass == InputType.TYPE_CLASS_NUMBER &&
            inputFlags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) bottom.add(".")
        bottom.add("0")
        bottom.add("⌫")
        rows.add(bottom)
        rows.add(listOf("ABC", "↵"))
        return rows
    }

    private fun key(label: String, color: Int, size: Float, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (label == "space") "English (US)" else label
            textSize = size
            setTextColor(if (color == accent) Color.rgb(4, 27, 29) else textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(7).toFloat()
            }
            isClickable = true
            isFocusable = true
            contentDescription = when (label) {
                "⌫" -> "Delete one character"
                "↵" -> "Enter or submit"
                "space" -> "Space"
                else -> label
            }
            setOnClickListener { action() }
        }
    }

    private fun handleKey(key: String) {
        val ic: InputConnection = currentInputConnection ?: return
        when (key) {
            "⇧" -> { upper = !upper; setInputView(buildKeyboard()) }
            "?123" -> { symbols = true; setInputView(buildKeyboard()) }
            "ABC" -> {
                if (numericMode || phoneMode) {
                    inputClass = InputType.TYPE_CLASS_TEXT
                    inputFlags = 0
                }
                symbols = false
                setInputView(buildKeyboard())
            }
            "⌫" -> ic.deleteSurroundingTextInCodePoints(1, 0)
            "space" -> ic.commitText(" ", 1)
            "↵" -> {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action != null && action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
            }
            "−" -> ic.commitText("-", 1)
            "," -> ic.commitText(",", 1)
            "." -> ic.commitText(".", 1)
            else -> {
                val value = key.trim()
                if (value.isNotEmpty()) ic.commitText(value, 1)
                if (!symbols && !numericMode && !phoneMode && upper &&
                    value.length == 1 && value[0].isLetter()) {
                    upper = false
                    setInputView(buildKeyboard())
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
