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
    @Synchronized
    fun execute(context: Context, code: String, explicitlyApproved: Boolean): String {
        PythonExecutionPolicy.rejection(code, explicitlyApproved, PythonExecutionPolicy.maxCodeChars(context))?.let { return "error: $it" }
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            Python.getInstance().getModule("orion_exec").callAttr("run", code).toString().take(5_200)
        } catch (e: Exception) {
            "error: bundled Python is unavailable (" + (e.message ?: e.javaClass.simpleName).take(180) + ")."
        }
    }

    /** Runs a generated extension in the whitelist sandbox; returns orion_exec.run_extension's JSON text. */
    @Synchronized
    fun runExtension(context: Context, source: String, inputJson: String): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("orion_exec").callAttr("run_extension", source, inputJson).toString()
    } catch (e: Exception) {
        org.json.JSONObject().put("ok", false)
            .put("error", "bundled Python is unavailable (" + (e.message ?: e.javaClass.simpleName).take(180) + ")").toString()
    }
}
