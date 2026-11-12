package com.phoneai.app

import org.json.JSONObject

/**
 * Fail-closed compatibility wrapper for generated extensions.
 *
 * Full CPython is available through the explicit python_exec capability, but it runs in Orion's
 * app-UID PRoot guest and is not an OS-enforced security boundary. It must not be used as an
 * auto-activated generated-extension sandbox. Generated extensions therefore stay on the
 * restricted, capability-free data-transform interpreter until a real isolated worker exists.
 */
@Deprecated("Unrestricted CPython is not an extension sandbox; use RestrictedPythonSandbox.")
internal class FullPythonSandbox(
    private val restricted: ExtensionSandbox
) : ExtensionSandbox {
    override fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport {
        if (extension.runtime == "python_full") {
            return ExtensionTestReport(
                false,
                SelfExtendingExtensionPolicy.sha256(extension.source),
                "python_full extensions are blocked: PRoot is not an OS security boundary. Use restricted python extensions or the explicit python_exec tool."
            )
        }
        return restricted.test(extension, testCases)
    }

    override fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject {
        if (extension.runtime == "python_full") {
            return JSONObject().put("ok", false).put(
                "error",
                "python_full extension execution is blocked because the PRoot runtime is not an OS-enforced sandbox."
            )
        }
        return restricted.execute(extension, input, grantedCapabilities)
    }
}
