package com.phoneai.app

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Bundled ARM64 Python runtime. This is intentionally a constrained data-processing interpreter,
 * not a general OS shell: imports, attributes, filesystem/network access and arbitrary callables are
 * rejected by the bundled AST policy. Caller must obtain explicit action approval first.
 */
internal object OrionPythonRuntime {
    private const val MAX_CODE_CHARS = 8_000

    @Synchronized
    fun execute(context: Context, code: String, explicitlyApproved: Boolean): String {
        if (!explicitlyApproved) return "error: Python execution requires explicit user approval."
        if (code.isBlank()) return "error: Python code is required."
        if (code.length > MAX_CODE_CHARS) return "error: Python code exceeds $MAX_CODE_CHARS characters."
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            Python.getInstance().getModule("orion_exec").callAttr("run", code).toString().take(5_200)
        } catch (e: Exception) {
            "error: bundled Python is unavailable (" + (e.message ?: e.javaClass.simpleName).take(180) + ")."
        }
    }
}
