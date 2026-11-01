package com.phoneai.app

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Bundled Python runtime. Direct scripts receive only the registered, policy-checked phone facade. */
internal object OrionPythonRuntime {
    @Synchronized
    fun execute(context: Context, code: String, confirm: (String) -> Boolean = { false }): String {
        PythonExecutionPolicy.rejection(code, PythonExecutionPolicy.maxCodeChars(context))?.let { return "error: $it" }
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            val phone = OrionPythonPhoneApi(context.applicationContext, confirm)
            Python.getInstance().getModule("orion_exec")
                .callAttr("run", code, PythonExecutionPolicy.maxCodeChars(context), phone)
                .toString().take(5_200)
        } catch (e: Exception) {
            "error: bundled Python failed safely (" + (e.message ?: e.javaClass.simpleName).take(180) + ")."
        }
    }

    /** Generated extensions deliberately do not receive the phone facade or host capabilities. */
    @Synchronized
    fun runExtension(context: Context, source: String, inputJson: String): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("orion_exec").callAttr("run_extension", source, inputJson).toString()
    } catch (e: Exception) {
        org.json.JSONObject().put("ok", false)
            .put("error", "bundled Python failed safely (" + (e.message ?: e.javaClass.simpleName).take(180) + ")").toString()
    }
}
