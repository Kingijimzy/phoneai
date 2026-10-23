package com.phoneai.app

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Orion's privacy-first keyboard.
 *
 * It never requests surrounding text, extracted text, selected text, or editor contents.
 * Key presses are sent directly through InputConnection.commitText/deleteSurroundingText.
 */
class OrionKeyboardService : InputMethodService() {
    private var upper = true
    private var symbols = false

    private val bg = Color.rgb(13, 17, 20)
    private val keyBg = Color.rgb(31, 39, 43)
    private val accent = Color.rgb(85, 224, 207)
    private val textColor = Color.rgb(239, 246, 247)

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val inputType = attribute?.inputType ?: 0
        upper = (inputType and (
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )) != 0
        symbols = false
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
        header.addView(key("ORION  ·  PRIVATE", accent, 12f) {}, LinearLayout.LayoutParams(0, dp(32), 1f))
        header.addView(key("⌄", keyBg, 18f) { requestHideSelf(0) }, LinearLayout.LayoutParams(dp(42), dp(32)))
        root.addView(header)

        val rows = if (symbols) {
            listOf(
                listOf("1","2","3","4","5","6","7","8","9","0"),
                listOf("@","#","$","%","&","-","+","(",")","/"),
                listOf("<",">","*","\u0022","' ",":",";","!","?","⌫"),
                listOf("ABC",",","space",".","↵")
            )
        } else {
            listOf(
                (if (upper) "QWERTYUIOP" else "qwertyuiop").map { it.toString() },
                (if (upper) "ASDFGHJKL" else "asdfghjkl").map { it.toString() },
                listOf("⇧") + (if (upper) "ZXCVBNM" else "zxcvbnm").map { it.toString() } + listOf("⌫"),
                listOf("?123",",","space",".","↵")
            )
        }

        rows.forEachIndexed { rowIndex, keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val rowLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(49))
            rowLp.topMargin = dp(4)
            keys.forEach { label ->
                val weight = when (label) {
                    "space" -> 4.5f
                    "⇧", "⌫", "?123", "ABC", "↵" -> 1.45f
                    else -> 1f
                }
                val keyView = key(label, if (label == "↵") accent else keyBg, if (label == "space") 12f else 16f) {
                    handleKey(label)
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                lp.marginStart = dp(2)
                lp.marginEnd = dp(2)
                row.addView(keyView, lp)
            }
            root.addView(row, rowLp)
        }
        return root
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
            setOnClickListener { action() }
        }
    }

    private fun handleKey(key: String) {
        val ic: InputConnection = currentInputConnection ?: return
        when (key) {
            "⇧" -> { upper = !upper; setInputView(buildKeyboard()) }
            "?123" -> { symbols = true; setInputView(buildKeyboard()) }
            "ABC" -> { symbols = false; setInputView(buildKeyboard()) }
            "⌫" -> ic.deleteSurroundingText(1, 0)
            "space" -> ic.commitText(" ", 1)
            "↵" -> {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
            }
            "," -> ic.commitText(",", 1)
            "." -> ic.commitText(".", 1)
            else -> {
                val value = key.trim()
                ic.commitText(value, 1)
                if (!symbols && upper && value.length == 1 && value[0].isLetter()) {
                    upper = false
                    setInputView(buildKeyboard())
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
