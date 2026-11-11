package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dev Agent — an integrated development specialist operating under the Central Brain.
 *
 * The Dev Agent understands repositories, reads and modifies multiple files, plans code
 * changes, executes tests, diagnoses build failures, repairs code, verifies changes and
 * generates project summaries. It is read-only-by-default on user workspaces: code edits
 * require an explicit workspace folder selection, and no generated code executes without
 * a separate owner-approved Python extension workflow.
 *
 * Honesty contract: the Dev Agent reports four distinct states — code written, code
 * compiled, tests passed, software installed — and never conflates one with another.
 */
internal class OrionDevAgent(
    private val context: Context,
    private val pythonExecutor: OrionPythonCapabilityExecutor
) {
    private val workspace = OrionPythonWorkspace(context)

    /** Result of a Dev Agent operation: what was attempted and what was actually verified. */
    data class DevResult(
        val action: String,
        val codeWritten: Boolean = false,
        val filesChanged: Int = 0,
        val compilationAttempted: Boolean = false,
        val compilationPassed: Boolean = false,
        val testsAttempted: Boolean = false,
        val testsPassed: Boolean = false,
        val installAttempted: Boolean = false,
        val installSucceeded: Boolean = false,
        val evidence: String = "",
        val blocker: String? = null,
        val summary: String = ""
    ) {
        fun statusLine(): String {
            val states = mutableListOf<String>()
            if (codeWritten) states += "code written ($filesChanged files)"
            if (compilationAttempted) states += if (compilationPassed) "compiled OK" else "compilation failed"
            if (testsAttempted) states += if (testsPassed) "tests passed" else "tests failed"
            if (installAttempted) states += if (installSucceeded) "installed OK" else "install failed"
            return if (states.isEmpty()) "no action verified" else states.joinToString(", ")
        }
    }

    /**
     * Understand a repository: inspect its structure, identify the project type,
     * list key source files and report what is present without modifying anything.
     */
    fun understandRepository(workspacePath: String): DevResult {
        if (!workspace.rootExists()) {
            return DevResult(
                action = "understand_repository",
                blocker = "no workspace available; select a workspace folder in Workspace Terminal first",
                summary = "Repository understanding requires an owner-selected workspace."
            )
        }
        val listing = workspace.list()
        if (listing.startsWith("error:")) {
            return DevResult(action = "understand_repository", blocker = listing, summary = "Could not list the workspace.")
        }
        val files = listing.lines().filter { it.isNotEmpty() }.toList()
        val projectType = detectProjectType(files)
        val keyFiles = files.filter { f ->
            f.endsWith(".kt") || f.endsWith(".java") || f.endsWith(".py") ||
                f.endsWith(".js") || f.endsWith(".ts") || f.endsWith(".json") ||
                f.endsWith(".gradle.kts") || f.endsWith(".build.gradle") ||
                f.equals("AndroidManifest.xml") || f.equals("pom.xml") || f.equals("build.gradle")
        }.take(20)

        return DevResult(
            action = "understand_repository",
            filesChanged = 0,
            evidence = "Workspace contains ${files.size} files. Detected project type: $projectType. " +
                "Key source/config files: ${keyFiles.joinToString(", ").ifBlank { "none detected" }}.",
            summary = "Repository inspection complete: $projectType project with ${files.size} workspace files. " +
                "No files were read or modified — this was a directory listing only."
        )
    }

    /** Inspect a single source file's contents without modifying it. */
    fun readSourceFile(workspacePath: String): DevResult {
        val content = workspace.read(workspacePath)
        if (content.startsWith("error:")) {
            return DevResult(action = "read_source_file", blocker = content, summary = "Could not read $workspacePath.")
        }
        return DevResult(
            action = "read_source_file",
            evidence = content.take(12_000),
            summary = "Read $workspacePath (${content.length} chars). No modifications were made."
        )
    }

    /**
     * Write or modify a source file. This creates an automatic backup before overwriting
     * any existing file (via OrionPythonWorkspace.backup). The caller must explicitly
     * request the write; the Dev Agent never modifies files on its own initiative.
     */
    fun writeSourceFile(workspacePath: String, content: String): DevResult {
        if (content.isBlank()) {
            return DevResult(action = "write_source_file", blocker = "empty content refused", summary = "No file was written.")
        }
        val result = workspace.write(workspacePath, content)
        if (result.startsWith("error:")) {
            return DevResult(action = "write_source_file", blocker = result, summary = "Write to $workspacePath was not saved.")
        }
        return DevResult(
            action = "write_source_file",
            codeWritten = true,
            filesChanged = 1,
            evidence = result,
            summary = "Wrote $workspacePath (${content.toByteArray().size} bytes). " +
                "A backup of the previous version was created automatically. Compilation and tests have not yet been run."
        )
    }

    /**
     * Execute Python code in the restricted sandbox. Returns a DevResult that clearly
     * distinguishes execution from compilation or test success.
     */
    fun runPythonCode(code: String, confirm: (String) -> Boolean = { false }, stopped: () -> Boolean = { false }): DevResult {
        if (code.isBlank()) {
            return DevResult(action = "run_python_code", blocker = "empty Python code refused", summary = "No Python was executed.")
        }
        val result = pythonExecutor.execute("python_exec", JSONObject().put("code", code), confirm, stopped)
        if (result == null || result.startsWith("error:")) {
            return DevResult(
                action = "run_python_code",
                blocker = result ?: "Python execution returned no result",
                summary = "Python code was not executed successfully."
            )
        }
        return DevResult(
            action = "run_python_code",
            evidence = (warning ?: "") + result.take(8_000),
            summary = "Python code executed in the sandbox. Output above is execution evidence, not proof of compilation or test success."
        )
    }

    /**
     * Run a shell command for build/test operations (e.g., gradle, kotlinc, pip).
     * Output is captured honestly; exit status is part of the evidence.
     * Uses the existing ShellRunner; shell risk classifications are warnings only.
     */
    fun runShellCommand(command: String, stopped: () -> Boolean = { false }): DevResult {
        if (stopped()) {
            return DevResult(action = "run_shell_command", blocker = "shell command cancelled before launch.", summary = "No shell command was executed.")
        }
        val warning = ShellPolicy.warning(command)
        val result = ShellRunner.runBuiltin(context, command)
        if (result.startsWith("error:")) {
            return DevResult(
                action = "run_shell_command",
                blocker = result,
                summary = "Shell command was not executed: $result"
            )
        }
        return DevResult(
            action = "run_shell_command",
            evidence = result.take(8_000),
            summary = "Shell command executed. Exit status and output are evidence; command acceptance is not task completion."
        )
    }

    /**
     * Plan a code change: inspect the target file, describe the proposed modification
     * without applying it, and list what verification steps would be needed.
     */
    fun planChange(workspacePath: String, proposedChange: String): DevResult {
        val current = workspace.read(workspacePath)
        if (current.startsWith("error:")) {
            return DevResult(action = "plan_change", blocker = current, summary = "Cannot plan a change to an unreadable file.")
        }
        val plan = buildString {
            appendLine("PLAN FOR $workspacePath")
            appendLine("Proposed change: $proposedChange")
            appendLine("Current size: ${current.length} bytes")
            appendLine("Verification required: read-back the written file, run project build/tests, confirm install artifact.")
            appendLine("Safety: this plan does not modify any file. Apply only through writeSourceFile after owner approval.")
        }
        return DevResult(
            action = "plan_change",
            evidence = plan,
            summary = "Change plan prepared for $workspacePath. No files were modified."
        )
    }

    /**
     * Generate a project summary: current state, what changed, what was verified,
     * and what remains to be done. This is the Dev Agent's honest status report.
     */
    fun projectSummary(): DevResult {
        val listing = workspace.list()
        val fileCount = if (listing.startsWith("error:")) 0 else listing.lines().filter { it.isNotEmpty() }.size
        val backupCount = workspace.backupCount()
        val trashCount = workspace.trashCount()
        return DevResult(
            action = "project_summary",
            evidence = "Workspace files: $fileCount · Automatic backups: $backupCount · Items in recycle: $trashCount",
            summary = "Dev Agent status: $fileCount workspace files tracked. " +
                "Use readSourceFile/writeSourceFile/runPythonCode/runShellCommand for individual steps. " +
                "No build or test result is claimed unless explicitly verified above."
        )
    }

    /** Detect project type from workspace file listing. */
    private fun detectProjectType(files: List<String>): String {
        if (files.any { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") || it.equals("settings.gradle.kts") || it.equals("settings.gradle") }) return "Android/Kotlin Gradle"
        if (files.any { it.equals("pom.xml") }) return "Java/Maven"
        if (files.any { it.endsWith(".py") }) return "Python"
        if (files.any { it.endsWith(".js") || it.endsWith(".ts") || it.equals("package.json") }) return "JavaScript/TypeScript"
        if (files.any { it.equals("AndroidManifest.xml") }) return "Android"
        return "unknown"
    }

    companion object {
        /** The Dev Agent never requires its own approval to run; the tools it calls enforce their own policies. */
        fun requiresExplicitApproval(): Boolean = false
    }
}