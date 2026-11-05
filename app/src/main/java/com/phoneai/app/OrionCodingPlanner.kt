package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Durable, dependency-ordered project plan adapted from Ultron Dev Agent.
 *
 * The planner owns the file DAG, write receipts, test state and bounded repair budget.
 * It does not generate code or bypass the Agent's normal tool/approval path.
 */
internal object OrionCodingPlanner {
    enum class Status { PLANNED, WRITING, FILES_READY, VERIFYING, REPAIR_REQUIRED, COMPLETED, FAILED, BLOCKED, CANCELLED }

    private data class FileSpec(
        val path: String,
        val purpose: String,
        val dependencies: List<String>
    )

    private const val STORE = "orion_coding_plans.json"
    const val MAX_FILES = 32
    const val MAX_REPAIR_ATTEMPTS = 2
    private const val MAX_OUTPUT_CHARS = 3_000
    private val lock = Any()
    private val projectNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")
    private val filePathPattern = Regex("^[A-Za-z0-9._/-]{1,160}$")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun readRoot(context: Context): JSONObject {
        val atomic = AtomicFile(File(context.applicationContext.filesDir, STORE))
        return try {
            atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: FileNotFoundException) {
            JSONObject()
        }
    }

    private fun persist(context: Context, root: JSONObject): Boolean {
        val atomic = AtomicFile(File(context.applicationContext.filesDir, STORE))
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    private fun safeRelativePath(value: String): Boolean {
        if (!filePathPattern.matches(value) || value.startsWith("/") || value.contains("\\") || value.contains(":")) return false
        val parts = value.split("/")
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val leaf = parts.last().lowercase()
        return leaf != ".env" && !leaf.startsWith(".env.")
    }

    private fun topologicalOrder(files: List<FileSpec>): List<String>? {
        val byPath = files.associateBy { it.path }
        val state = HashMap<String, Int>()
        val order = ArrayList<String>()
        fun visit(path: String): Boolean {
            when (state[path]) {
                1 -> return false
                2 -> return true
            }
            val spec = byPath[path] ?: return false
            state[path] = 1
            for (dependency in spec.dependencies) if (!visit(dependency)) return false
            state[path] = 2
            order += path
            return true
        }
        for (file in files) if (!visit(file.path)) return null
        return order
    }

    fun create(
        context: Context,
        rawName: String,
        rawLanguage: String,
        objective: String,
        entryPoint: String,
        rawFiles: JSONArray?
    ): String = synchronized(lock) {
        val name = rawName.trim()
        val language = rawLanguage.trim().lowercase()
        val goal = objective.trim().take(1_200)
        val entry = entryPoint.trim()
        if (!projectNamePattern.matches(name)) return@synchronized "error: invalid project name."
        if (language !in setOf("python", "javascript", "kotlin", "android")) return@synchronized "error: unsupported project language."
        if (goal.isBlank()) return@synchronized "error: coding objective is required."
        if (rawFiles == null || rawFiles.length() !in 1..MAX_FILES) {
            return@synchronized "error: provide between 1 and $MAX_FILES planned files."
        }

        val workspace = OrionPythonWorkspace(context.applicationContext)
        val manifestText = workspace.read("projects/$name/project.json")
        if (manifestText.startsWith("error:")) return@synchronized "error: create or locate project '$name' before planning it."
        val manifestLanguage = runCatching { JSONObject(manifestText).optString("language") }.getOrDefault("")
        if (manifestLanguage != language) return@synchronized "error: project language is '$manifestLanguage', not '$language'."

        val specs = ArrayList<FileSpec>()
        val seen = HashSet<String>()
        for (index in 0 until rawFiles.length()) {
            val row = rawFiles.optJSONObject(index) ?: return@synchronized "error: file plan row $index is not an object."
            val path = row.optString("path").trim()
            val purpose = row.optString("purpose").trim().take(240)
            if (!safeRelativePath(path)) return@synchronized "error: unsafe project-relative file path '$path'."
            if (!seen.add(path)) return@synchronized "error: duplicate planned file '$path'."
            if (purpose.isBlank()) return@synchronized "error: planned file '$path' needs a purpose."
            val dependenciesJson = row.optJSONArray("depends_on") ?: JSONArray()
            if (dependenciesJson.length() > MAX_FILES) return@synchronized "error: too many dependencies for '$path'."
            val dependencies = (0 until dependenciesJson.length()).map { dependenciesJson.optString(it).trim() }
            if (dependencies.any { !safeRelativePath(it) } || dependencies.distinct().size != dependencies.size || path in dependencies) {
                return@synchronized "error: invalid dependency list for '$path'."
            }
            specs += FileSpec(path, purpose, dependencies)
        }
        if (entry !in seen) return@synchronized "error: entry_point '$entry' must be one of the planned files."
        val unknown = specs.flatMap { it.dependencies }.filterNot { it in seen }.distinct()
        if (unknown.isNotEmpty()) return@synchronized "error: dependencies are not in the plan: ${unknown.joinToString()}."
        val order = topologicalOrder(specs)
            ?: return@synchronized "error: file dependency cycle detected; no plan was saved."

        val root = try { readRoot(context) } catch (_: Exception) {
            return@synchronized "error: coding plan store is unreadable; existing plans were left untouched."
        }
        val old = root.optJSONObject(name)
        if (old != null && old.optString("status") !in setOf("COMPLETED", "FAILED", "CANCELLED")) {
            return@synchronized "error: project '$name' already has an active coding plan; inspect or cancel it before replacing it."
        }

        val fileRows = JSONArray()
        specs.forEach { spec ->
            fileRows.put(JSONObject()
                .put("path", spec.path)
                .put("purpose", spec.purpose)
                .put("depends_on", JSONArray(spec.dependencies))
                .put("status", "pending")
                .put("sha256", ""))
        }
        val canonical = name + "|" + language + "|" + goal + "|" + entry + "|" +
            specs.joinToString("|") { it.path + ":" + it.dependencies.sorted().joinToString(",") }
        val now = System.currentTimeMillis()
        val plan = JSONObject()
            .put("plan_id", digest(canonical))
            .put("name", name)
            .put("language", language)
            .put("objective", goal)
            .put("entry_point", entry)
            .put("files", fileRows)
            .put("order", JSONArray(order))
            .put("status", Status.PLANNED.name)
            .put("repair_attempts", 0)
            .put("repair_active", false)
            .put("test_command", "")
            .put("test_output", "")
            .put("created_at", now)
            .put("updated_at", now)
        root.put(name, plan)
        if (!persist(context, root)) return@synchronized "error: coding plan could not be saved; no plan creation is claimed."
        "CODING PLAN SAVED: $name (${language})\nDependency order:\n" +
            order.joinToString("\n") { "- $it" } +
            "\nEntry point: $entry\nFiles: ${specs.size}\nNext: write files in this order, then call project_verify."
    }

    /** Enforces the declared dependency order before a planned file is modified. */
    fun writeGate(context: Context, rawWorkspacePath: String): String? = synchronized(lock) {
        val path = rawWorkspacePath.trim().removePrefix("/workspace/").removePrefix("/")
        val parts = path.split("/")
        if (parts.size < 3 || parts[0] != "projects" || !projectNamePattern.matches(parts[1])) return@synchronized null
        val root = try { readRoot(context) } catch (_: Exception) {
            return@synchronized "error: coding plan store is unreadable; refusing project writes until it is repaired."
        }
        val plan = root.optJSONObject(parts[1]) ?: return@synchronized null
        if (plan.optString("status") in setOf(Status.COMPLETED.name, Status.FAILED.name, Status.CANCELLED.name)) return@synchronized null
        val relative = parts.drop(2).joinToString("/")
        val files = plan.optJSONArray("files") ?: return@synchronized null
        val row = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.firstOrNull {
            it.optString("path") == relative
        } ?: return@synchronized null
        val dependencies = row.optJSONArray("depends_on") ?: JSONArray()
        val unwritten = (0 until dependencies.length()).map { dependencies.optString(it) }.filter { dependency ->
            (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .firstOrNull { it.optString("path") == dependency }
                ?.optString("status") != "written"
        }
        if (unwritten.isEmpty()) null
        else "error: coding plan dependency order: write and verify these dependencies first: ${unwritten.joinToString()}."
    }

    /** Called after a successful workspace_write; records the actual saved bytes, not model claims. */
    fun noteFileWritten(context: Context, rawWorkspacePath: String): Boolean = synchronized(lock) {
        val path = rawWorkspacePath.trim().removePrefix("/workspace/").removePrefix("/")
        val parts = path.split("/")
        if (parts.size < 3 || parts[0] != "projects" || !projectNamePattern.matches(parts[1])) return@synchronized false
        val name = parts[1]
        val relative = parts.drop(2).joinToString("/")
        val root = try { readRoot(context) } catch (_: Exception) { return@synchronized false }
        val plan = root.optJSONObject(name) ?: return@synchronized false
        if (plan.optString("status") in setOf(Status.COMPLETED.name, Status.FAILED.name, Status.CANCELLED.name)) return@synchronized false
        val fileText = OrionPythonWorkspace(context.applicationContext).read(path)
        if (fileText.startsWith("error:")) return@synchronized false
        val files = plan.optJSONArray("files") ?: return@synchronized false
        val row = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.firstOrNull {
            it.optString("path") == relative
        } ?: return@synchronized false

        val nextHash = digest(fileText)
        if (plan.optString("status") == Status.REPAIR_REQUIRED.name && !plan.optBoolean("repair_active")) {
            if (row.optString("sha256") == nextHash) return@synchronized false
            val attempts = plan.optInt("repair_attempts")
            if (attempts >= MAX_REPAIR_ATTEMPTS) {
                plan.put("status", Status.FAILED.name).put("updated_at", System.currentTimeMillis())
                persist(context, root)
                return@synchronized false
            }
            plan.put("repair_attempts", attempts + 1).put("repair_active", true)
        }
        row.put("status", "written").put("sha256", nextHash)
        plan.put("status", Status.WRITING.name).put("updated_at", System.currentTimeMillis())
        persist(context, root)
    }

    fun status(context: Context, rawName: String): String = synchronized(lock) {
        val name = rawName.trim()
        if (!projectNamePattern.matches(name)) return@synchronized "error: invalid project name."
        val plan = try { readRoot(context).optJSONObject(name) } catch (_: Exception) {
            return@synchronized "error: coding plan store is unreadable."
        } ?: return@synchronized "No structured coding plan is saved for '$name'."
        val files = plan.optJSONArray("files") ?: JSONArray()
        val rows = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
        val written = rows.count { it.optString("status") == "written" }
        val missing = rows.filter { it.optString("status") != "written" }.map { it.optString("path") }
        "CODING PLAN ${plan.optString("name")}: status=${plan.optString("status")}; " +
            "files_written=$written/${rows.size}; repair_attempts=${plan.optInt("repair_attempts")}/$MAX_REPAIR_ATTEMPTS; " +
            "entry_point=${plan.optString("entry_point")}\n" +
            "Dependency order: " + (plan.optJSONArray("order")?.let { a -> (0 until a.length()).joinToString(" → ") { a.optString(it) } } ?: "unavailable") +
            "\nPending files: " + missing.joinToString().ifBlank { "none" } +
            "\nLast test command: ${plan.optString("test_command").ifBlank { "not run" }}\n" +
            "Last test output: ${plan.optString("test_output").take(800).ifBlank { "not run" }}"
    }

    fun verify(
        context: Context,
        rawName: String,
        runTests: Boolean,
        confirm: (String) -> Boolean
    ): String {
        val name = rawName.trim()
        val preflight = synchronized(lock) {
            if (!projectNamePattern.matches(name)) return@synchronized "error: invalid project name."
            val root = try { readRoot(context) } catch (_: Exception) { return@synchronized "error: coding plan store is unreadable." }
            val plan = root.optJSONObject(name) ?: return@synchronized "error: no coding plan exists for '$name'."
            if (plan.optString("status") in setOf(Status.CANCELLED.name, Status.FAILED.name)) {
                return@synchronized "error: coding plan is ${plan.optString("status").lowercase()} and cannot be verified again."
            }
            val files = plan.optJSONArray("files") ?: JSONArray()
            val workspace = OrionPythonWorkspace(context.applicationContext)
            val missing = mutableListOf<String>()
            var changedDuringRepair = false
            for (index in 0 until files.length()) {
                val row = files.optJSONObject(index) ?: continue
                val relative = row.optString("path")
                val content = workspace.read("projects/$name/$relative")
                if (content.startsWith("error:")) {
                    missing += relative
                } else {
                    val currentHash = digest(content)
                    if (plan.optString("status") == Status.REPAIR_REQUIRED.name &&
                        row.optString("sha256").isNotBlank() && row.optString("sha256") != currentHash) {
                        changedDuringRepair = true
                    }
                    row.put("status", "written").put("sha256", currentHash)
                }
            }
            if (missing.isNotEmpty()) {
                if (plan.optString("status") != Status.REPAIR_REQUIRED.name) plan.put("status", Status.WRITING.name)
                plan.put("updated_at", System.currentTimeMillis())
                persist(context, root)
                return@synchronized "INCOMPLETE: missing planned files: ${missing.joinToString()}. Write these before testing."
            }
            if (plan.optString("status") == Status.REPAIR_REQUIRED.name && !plan.optBoolean("repair_active")) {
                if (!changedDuringRepair) {
                    return@synchronized "REPAIR_REQUIRED: no planned file changed since the previous failed test; modify the relevant file before rerunning verification."
                }
                val attempts = plan.optInt("repair_attempts")
                if (attempts >= MAX_REPAIR_ATTEMPTS) {
                    plan.put("status", Status.FAILED.name).put("updated_at", System.currentTimeMillis())
                    persist(context, root)
                    return@synchronized "FAILED: repair attempt budget exhausted."
                }
                plan.put("repair_attempts", attempts + 1).put("repair_active", true)
            }
            if (!runTests) {
                plan.put("status", Status.FILES_READY.name).put("updated_at", System.currentTimeMillis())
                persist(context, root)
                return@synchronized "FILES_READY: all ${files.length()} planned files exist and were read back. Tests have not been run. Call project_verify with run_tests=true to execute the fixed project test command."
            }
            if (!hasPlannedTests(plan.optString("language"), files)) {
                plan.put("status", Status.FILES_READY.name).put("updated_at", System.currentTimeMillis())
                persist(context, root)
                return@synchronized "TESTS_NOT_PLANNED: add at least one language-appropriate test file to the coding plan before executing tests."
            }
            plan.put("status", Status.VERIFYING.name).put("updated_at", System.currentTimeMillis())
            if (!persist(context, root)) return@synchronized "error: verification state could not be persisted; tests were not started."
            plan.optString("language")
        }
        if (preflight.startsWith("error:") || preflight.startsWith("INCOMPLETE:") || preflight.startsWith("FILES_READY:")) return preflight
        val language = preflight
        val command = testCommand(language)
            ?: return "error: no automatic test command is defined for '$language'."
        val prompt = "Run the fixed $language project test command in Orion's Alpine PRoot runtime? This executes project code under Orion's Android app UID; PRoot is not a complete security boundary. Continue?"
        if (!confirm(prompt)) {
            setStatus(context, name, Status.FILES_READY, command, "Test execution cancelled by owner; no project code was executed.")
            return "TEST EXECUTION CANCELLED: no project code was executed."
        }
        val output = ShellRunner.run(
            context.applicationContext,
            "cd /workspace/projects/$name && $command",
            "linux"
        )
        val exitCode = Regex("(?m)^exit[=\\s]+(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val missingRuntime = exitCode == 127
        if (output.startsWith("error:", ignoreCase = true) || missingRuntime || output.contains("timed out", ignoreCase = true)) {
            setStatus(context, name, Status.BLOCKED, command, output)
            return "TEST RUN BLOCKED: the test command did not complete successfully in Orion Alpine (missing runtime or timeout). $output"
        }
        if (exitCode == 0 && noTestsExecuted(language, output)) {
            setStatus(context, name, Status.BLOCKED, command, output)
            return "TEST RUN BLOCKED: the test runner discovered zero tests; add or correct the planned test files before claiming verification."
        }
        return recordTestResult(context, name, command, output, exitCode)
    }

    internal fun recordTestResult(
        context: Context,
        rawName: String,
        command: String,
        output: String,
        exitCode: Int
    ): String = synchronized(lock) {
        val name = rawName.trim()
        val root = try { readRoot(context) } catch (_: Exception) {
            return@synchronized "error: coding plan store is unreadable."
        }
        val plan = root.optJSONObject(name) ?: return@synchronized "error: no coding plan exists for '$name'."
        val safeOutput = AgentPrivacyFilter.safeCommand(output).take(MAX_OUTPUT_CHARS)
        val passed = exitCode == 0 && !Regex("(?m)^FAILED\\b|^FAIL:").containsMatchIn(safeOutput)
        val next = when {
            passed -> Status.COMPLETED
            plan.optInt("repair_attempts") >= MAX_REPAIR_ATTEMPTS -> Status.FAILED
            else -> Status.REPAIR_REQUIRED
        }
        plan.put("status", next.name)
            .put("repair_active", false)
            .put("test_command", command.take(240))
            .put("test_output", safeOutput)
            .put("updated_at", System.currentTimeMillis())
        if (!persist(context, root)) return@synchronized "error: test result was observed but its durable receipt could not be saved."
        when (next) {
            Status.COMPLETED -> "VERIFIED: project '$name' passed its test command. Test output:\n$safeOutput"
            Status.REPAIR_REQUIRED -> "REPAIR_REQUIRED: project '$name' tests failed (exit $exitCode). Repair attempt budget: ${plan.optInt("repair_attempts")}/$MAX_REPAIR_ATTEMPTS. Inspect the failure, change the relevant planned files, then run project_verify again. Do not repeat the same unchanged test."
            else -> "FAILED: project '$name' exhausted its $MAX_REPAIR_ATTEMPTS repair attempts. Test output:\n$safeOutput"
        }
    }

    fun cancel(context: Context, rawName: String): String = synchronized(lock) {
        val name = rawName.trim()
        val root = try { readRoot(context) } catch (_: Exception) {
            return@synchronized "error: coding plan store is unreadable."
        }
        val plan = root.optJSONObject(name) ?: return@synchronized "error: no coding plan exists for '$name'."
        if (plan.optString("status") == Status.COMPLETED.name) return@synchronized "error: completed coding plan cannot be cancelled."
        plan.put("status", Status.CANCELLED.name).put("repair_active", false).put("updated_at", System.currentTimeMillis())
        if (persist(context, root)) "coding plan '$name' cancelled; project files were retained."
        else "error: cancellation could not be persisted."
    }

    private fun setStatus(context: Context, name: String, status: Status, command: String, output: String) {
        synchronized(lock) {
            val root = runCatching { readRoot(context) }.getOrNull() ?: return
            val plan = root.optJSONObject(name) ?: return
            plan.put("status", status.name)
                .put("test_command", command.take(240))
                .put("test_output", AgentPrivacyFilter.safeCommand(output).take(MAX_OUTPUT_CHARS))
                .put("updated_at", System.currentTimeMillis())
            persist(context, root)
        }
    }

    private fun hasPlannedTests(language: String, files: JSONArray): Boolean {
        val paths = (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optString("path") }
        return when (language) {
            "python" -> paths.any { it.startsWith("tests/") && it.endsWith(".py") && (it.substringAfterLast('/').startsWith("test_") || it.endsWith("_test.py")) }
            "javascript" -> paths.any { it.startsWith("test/") || it.startsWith("tests/") || Regex("\\.(test|spec)\\.(js|mjs|cjs|ts)$").containsMatchIn(it) }
            "kotlin" -> paths.any { it.contains("src/test/") }
            "android" -> paths.any { it.contains("src/test/") || it.contains("src/androidTest/") }
            else -> false
        }
    }

    private fun noTestsExecuted(language: String, output: String): Boolean = when (language) {
        "python" -> Regex("(?m)^Ran 0 tests?").containsMatchIn(output)
        "javascript" -> Regex("(?i)tests\\s+0\\b").containsMatchIn(output)
        else -> false
    }

    private fun testCommand(language: String): String? = when (language) {
        "python" -> "python3 -m unittest discover -s tests -v"
        "javascript" -> "node --test"
        "kotlin" -> "gradle test --no-daemon"
        "android" -> "gradle :app:testDebugUnitTest --no-daemon"
        else -> null
    }
}
