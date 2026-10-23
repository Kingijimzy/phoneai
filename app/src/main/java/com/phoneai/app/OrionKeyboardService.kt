package com.phoneai.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.View
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
        setInputView(buildKeyboard())
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
