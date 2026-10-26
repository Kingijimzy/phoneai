package com.phoneai.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 */
class OrionKeyboardService : InputMethodService() {
    companion object {
        private data class TypeRequest(
            val text: String,
            val targetPackage: String?,
            val done: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
            @Volatile var result: String = "error: Orion keyboard did not accept the text request"
        )

        @Volatile private var active: OrionKeyboardService? = null
        @Volatile private var pending: TypeRequest? = null

        /**
         * Sends an explicit agent-provided string through the active IME connection.
         * It never reads text back from the editor. If Orion is not the active IME,
         * it opens Android's system picker and waits for the user to select it.
         */
        fun typeIntoFocusedField(context: android.content.Context, text: String, targetPackage: String?): String {
            OrionKeyboardPolicy.validateRequest(text)?.let { return it }
            val service = active
            val selectedIme = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
                )
            } catch (_: Exception) { null }
            val ourIme = android.content.ComponentName(context, OrionKeyboardService::class.java)
                .flattenToShortString()
            // Do not trust a stale InputConnection retained after Android switches to another IME.
            if (service != null && selectedIme == ourIme && service.currentInputConnection != null) {
                return service.commitAgentText(text, targetPackage)
            }
            val request = TypeRequest(text, targetPackage)
            synchronized(OrionKeyboardService::class.java) {
                if (pending != null) return "error: another keyboard typing request is already waiting"
                pending = request
            }
            try {
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
                if (!request.done.await(25, java.util.concurrent.TimeUnit.SECONDS)) {
                    synchronized(OrionKeyboardService::class.java) {
                        if (pending === request) pending = null
                    }
                    return "error: Orion Private Keyboard was not selected in Android's keyboard picker. Select it and retry the typing action."
                }
                return request.result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                synchronized(OrionKeyboardService::class.java) {
                    if (pending === request) pending = null
                }
                return "error: typing was interrupted before Orion Keyboard became active"
            } catch (e: Exception) {
                synchronized(OrionKeyboardService::class.java) {
                    if (pending === request) pending = null
                }
                return "error: could not open the Android keyboard picker: ${e.javaClass.simpleName}"
            }
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
        val request = synchronized(OrionKeyboardService::class.java) { pending }
        // Agent typing uses a 1×1 invisible IME view; the normal keyboard is not shown
        // over the target app. Ordinary user keyboard sessions still use the full UI.
        setInputView(if (request != null) buildAgentInputView() else buildKeyboard())
        if (request != null) {
            request.result = commitAgentText(request.text, request.targetPackage)
            synchronized(OrionKeyboardService::class.java) {
                if (pending === request) pending = null
            }
            request.done.countDown()
        }
    }

    private fun buildAgentInputView(): View = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(1, 1)
        visibility = View.INVISIBLE
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        // Return to the IME the user had selected before Orion was temporarily activated.
        // InputMethodService requires this switch on its main thread, after commitText completes.
        Handler(Looper.getMainLooper()).postDelayed({
            try { switchToPreviousInputMethod() } catch (_: Exception) { }
        }, 250L)
        return "typed via Orion Private Keyboard input connection; requested restoration of the previous keyboard"
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
