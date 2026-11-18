package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Orion's general-purpose coding space on top of the Linux runtime: toolchain profiles, virtualenvs, project scaffolds,
 * supervised local services, scheduled jobs, backups and a project viewer. Every builder here is a pure string function
 * (so it is tested), and `run` sends the script to the guest through the same shell route as everything else.
 */
internal object OrionDevspace {
    private val safeName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$")
    fun validName(s: String) = safeName.matches(s)
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    val profiles: Map<String, List<String>> = mapOf(
        "base" to listOf("python3", "py3-pip", "py3-virtualenv", "nodejs", "npm", "git", "bash", "curl", "wget", "jq", "build-base", "openssh-client", "sqlite", "rsync", "zip", "unzip", "tar", "xz", "ca-certificates"),
        "web" to listOf("nginx", "php83", "go"),
        "data" to listOf("sqlite", "postgresql-client", "gnuplot", "p7zip"),
        "media" to listOf("ffmpeg", "imagemagick", "exiftool"),
        "db" to listOf("sqlite", "postgresql", "redis"),
        "net" to listOf("nmap", "bind-tools", "iproute2", "socat", "tcpdump", "mosquitto-clients")
    )

    fun setupScript(profile: String): String? {
        val names = if (profile == "all") profiles.values.flatten().distinct() else profiles[profile] ?: return null
        return "set -e; apk update; apk add --no-cache " + names.joinToString(" ") + "; echo SETUP_DONE"
    }

    fun venvScript(name: String, packages: List<String>): String? {
        if (!validName(name) || packages.any { !Regex("^[A-Za-z0-9_.\\[\\]=<>!~,-]{1,80}$").matches(it) }) return null
        val dir = "/workspace/.venvs/$name"
        return "mkdir -p /workspace/.venvs && (test -d $dir || python3 -m venv $dir) && . $dir/bin/activate && pip install -q --upgrade pip" +
            (if (packages.isEmpty()) "" else " && pip install -q " + packages.joinToString(" ") { q(it) }) + " && echo VENV_READY $dir"
    }

    fun runScript(project: String, venv: String, command: String): String? {
        if (command.isBlank()) return null
        if (project.isNotEmpty() && !validName(project)) return null
        if (venv.isNotEmpty() && !validName(venv)) return null
        return buildString {
            append("cd /workspace" + if (project.isNotEmpty()) "/$project" else "")
            if (venv.isNotEmpty()) append(" && . /workspace/.venvs/$venv/bin/activate")
            append(" && ").append(command)
        }
    }

    /** Project scaffolds: real runnable starting points, written with heredocs so one guest command creates them. */
    fun scaffold(name: String, kind: String): String? {
        if (!validName(name)) return null
        val d = "/workspace/$name"
        fun file(path: String, body: String) = "cat > $d/$path <<'ORION_EOF'\n$body\nORION_EOF\n"
        val files = when (kind) {
            "python", "cli" -> file("main.py", "import sys\n\n\ndef main(argv):\n    print('hello from $name', argv[1:])\n    return 0\n\n\nif __name__ == '__main__':\n    sys.exit(main(sys.argv))") + file("requirements.txt", "") + file("README.md", "# $name\n\nRun: python3 main.py")
            "api" -> file("server.py", "import json\nfrom http.server import BaseHTTPRequestHandler, HTTPServer\n\n\nclass H(BaseHTTPRequestHandler):\n    def do_GET(self):\n        body = json.dumps({'ok': True, 'path': self.path}).encode()\n        self.send_response(200)\n        self.send_header('Content-Type', 'application/json')\n        self.send_header('Content-Length', str(len(body)))\n        self.end_headers()\n        self.wfile.write(body)\n\n\nHTTPServer(('0.0.0.0', 8080), H).serve_forever()") + file("README.md", "# $name\n\nJSON API on :8080. Start with service_start.")
            "node" -> file("package.json", "{\n  \"name\": \"$name\",\n  \"version\": \"0.1.0\",\n  \"main\": \"index.js\",\n  \"scripts\": { \"start\": \"node index.js\" }\n}") + file("index.js", "require('http').createServer((req, res) => {\n  res.setHeader('Content-Type', 'application/json');\n  res.end(JSON.stringify({ ok: true, path: req.url }));\n}).listen(3000, '0.0.0.0');") + file("README.md", "# $name\n\nnpm start (port 3000)")
            "web" -> file("index.html", "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>$name</title><h1>$name</h1><p>Served from Orion.</p>") + file("README.md", "# $name\n\nStatic site. Serve with: python3 -m http.server 8000")
            "android" -> androidFiles(name, ::file)
            else -> return null
        }
        return "mkdir -p $d && cd $d && (git init -q 2>/dev/null; true)\n$files" + "ls -la $d"
    }

    /** A minimal Kotlin Android app plus a GitHub Actions workflow that builds the debug APK and uploads it as an artifact. */
    private fun androidFiles(name: String, file: (String, String) -> String): String {
        val pkg = "com.orion." + name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val dir = pkg.replace('.', '/')
        return "mkdir -p .github/workflows app/src/main/java/$dir\n" +
            file("settings.gradle.kts", "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = \"$name\"\ninclude(\":app\")") +
            file("build.gradle.kts", "plugins {\n    id(\"com.android.application\") version \"8.5.2\" apply false\n    id(\"org.jetbrains.kotlin.android\") version \"1.9.24\" apply false\n}") +
            file("gradle.properties", "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g") +
            file("app/build.gradle.kts", "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\nandroid {\n    namespace = \"$pkg\"\n    compileSdk = 34\n    defaultConfig { applicationId = \"$pkg\"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = \"0.1.0\" }\n    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }\n    kotlinOptions { jvmTarget = \"17\" }\n}\ndependencies {\n    testImplementation(\"junit:junit:4.13.2\")\n}") +
            file("app/src/main/AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"$name\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>") +
            file("app/src/main/java/$dir/MainActivity.kt", "package $pkg\n\nimport android.app.Activity\nimport android.os.Bundle\nimport android.widget.TextView\n\nclass MainActivity : Activity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(TextView(this).apply { text = \"$name is running\"; textSize = 24f; setPadding(48, 96, 48, 48) })\n    }\n}") +
            file(".github/workflows/android.yml", ANDROID_WORKFLOW)
    }

    private const val ANDROID_WORKFLOW = "name: Android build\non:\n  push:\n  pull_request:\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with: { distribution: temurin, java-version: 17 }\n      - uses: gradle/actions/setup-gradle@v4\n        with: { gradle-version: 8.9 }\n      - run: gradle testDebugUnitTest assembleDebug\n      - uses: actions/upload-artifact@v4\n        with:\n          name: app-debug-apk\n          path: app/build/outputs/apk/debug/app-debug.apk"

    fun backupScript(project: String): String? =
        if (!validName(project)) null else "mkdir -p /workspace/backups && tar czf /workspace/backups/$project-\$(date +%Y%m%d-%H%M%S).tgz -C /workspace $project && ls -la /workspace/backups | tail -n 5"

    fun viewScript(project: String): String? {
        if (!validName(project)) return null
        val d = "/workspace/$project"
        return "cd $d 2>/dev/null || { echo 'no such project: $project'; exit 1; }; echo '== files =='; find . -maxdepth 3 -not -path './.git/*' -not -path './node_modules/*' -not -path './.venv/*' | head -n 60; " +
            "echo '== git =='; (git status -sb 2>/dev/null | head -n 15; git log --oneline -n 5 2>/dev/null) || true; echo '== readme =='; head -n 15 README.md 2>/dev/null; echo '== size =='; du -sh . 2>/dev/null"
    }

    // ---------- services ----------
    data class Svc(val name: String, val command: String, val port: Int, val startedAt: Long, @Volatile var restarts: Int = 0, @Volatile var running: Boolean = true)
    private val services = ConcurrentHashMap<String, Svc>()
    private const val MAX_RESTARTS = 20

    fun wrapService(name: String, command: String, project: String): String =
        "mkdir -p /workspace/.services && cd /workspace" + (if (project.isNotEmpty()) "/$project" else "") +
            " && sh -c ': ORION_SVC_$name; exec $command' >> /workspace/.services/$name.log 2>&1"

    fun stopScript(name: String): String = "pkill -f ORION_SVC_$name 2>/dev/null; echo stopped $name"

    fun healthScript(port: Int): String = "curl -s -m 4 -o /dev/null -w 'HTTP %{http_code}' http://127.0.0.1:$port/ || echo 'no answer on :$port'"

    fun startService(c: Context, name: String, command: String, port: Int, project: String): String {
        if (!validName(name)) return "error: service name must be letters, digits, dot, dash, underscore."
        if (command.isBlank()) return "error: service_start needs a command."
        if (project.isNotEmpty() && !validName(project)) return "error: bad project name."
        services[name]?.takeIf { it.running }?.let { return "error: service $name is already running (service_stop first)." }
        val svc = Svc(name, command, port, System.currentTimeMillis())
        services[name] = svc
        Thread({
            while (svc.running && svc.restarts <= MAX_RESTARTS) {
                ShellRunner.run(c, wrapService(name, command, project), OrionUbuntu.where(c))
                if (!svc.running) break
                svc.restarts++
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
            svc.running = false
        }, "orion-svc-$name").apply { isDaemon = true }.start()
        return "Service $name started" + (if (port > 0) " on port $port (reach it at http://127.0.0.1:$port/ from the phone or any Orion tool)" else "") +
            ". It restarts itself if it exits. Logs: service_logs {name:\"$name\"}."
    }

    fun stopService(c: Context, name: String): String {
        val s = services[name] ?: return "error: no service $name."
        s.running = false
        ShellRunner.run(c, stopScript(name), OrionUbuntu.where(c))
        return "Service $name stopped."
    }

    fun listServices(): String = if (services.isEmpty()) "No services running." else services.values.joinToString("\n") {
        "- ${it.name} [${if (it.running) "running" else "stopped"}] port=${it.port} restarts=${it.restarts} cmd=${it.command.take(60)}"
    }

    // ---------- jobs ----------
    data class Job(val name: String, val command: String, val everyMinutes: Int, val dailyAt: String, val lastRun: Long, val lastResult: String)

    /** Pure: is the job due at [now]? dailyAt "HH:MM" local; everyMinutes interval otherwise. */
    fun isDue(j: Job, now: Long, hour: Int, minute: Int): Boolean = when {
        j.dailyAt.isNotEmpty() -> {
            val (h, m) = j.dailyAt.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: -1) to (it.getOrNull(1)?.toIntOrNull() ?: -1) }
            h >= 0 && m >= 0 && (hour * 60 + minute) >= (h * 60 + m) && now - j.lastRun > 20 * 3_600_000L
        }
        j.everyMinutes > 0 -> now - j.lastRun >= j.everyMinutes * 60_000L - 30_000L
        else -> false
    }

    private const val PREFS = "orion_jobs_v1"
    private fun load(c: Context): MutableList<Job> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("jobs", "[]"))
        MutableList(a.length()) { i -> a.getJSONObject(i).let { Job(it.getString("name"), it.getString("command"), it.optInt("every"), it.optString("daily"), it.optLong("last"), it.optString("result")) } }
    }.getOrDefault(mutableListOf())

    private fun save(c: Context, jobs: List<Job>) {
        val a = JSONArray(); jobs.forEach { a.put(JSONObject().put("name", it.name).put("command", it.command).put("every", it.everyMinutes).put("daily", it.dailyAt).put("last", it.lastRun).put("result", it.lastResult)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("jobs", a.toString()).apply()
    }

    fun addJob(c: Context, name: String, command: String, every: Int, daily: String): String {
        if (!validName(name)) return "error: job name must be letters, digits, dot, dash, underscore."
        if (command.isBlank()) return "error: job_add needs a command."
        if (every <= 0 && !Regex("^\\d{1,2}:\\d{2}$").matches(daily)) return "error: give every_minutes (>=10) or daily_at \"HH:MM\"."
        synchronized(this) {
            val jobs = load(c).filterNot { it.name == name }.toMutableList()
            jobs += Job(name, command, if (every > 0) every.coerceAtLeast(10) else 0, if (every > 0) "" else daily, 0L, "")
            save(c, jobs)
        }
        return "Job $name scheduled (" + (if (every > 0) "every ${every.coerceAtLeast(10)} min" else "daily at $daily") + "). It runs in the Linux space and failures go to Telegram."
    }

    fun removeJob(c: Context, name: String): String = synchronized(this) {
        val jobs = load(c); val keep = jobs.filterNot { it.name == name }
        if (keep.size == jobs.size) "error: no job $name." else { save(c, keep); "Job $name removed." }
    }

    fun listJobs(c: Context): String = load(c).let { js ->
        if (js.isEmpty()) "No scheduled jobs." else js.joinToString("\n") {
            "- ${it.name} ${if (it.dailyAt.isNotEmpty()) "daily ${it.dailyAt}" else "every ${it.everyMinutes}m"} last=${if (it.lastRun == 0L) "never" else java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US).format(java.util.Date(it.lastRun))} ${it.lastResult.take(60)}"
        }
    }

    /** Called from the 10-minute service tick on a worker thread. */
    fun runDue(c: Context, now: Long = System.currentTimeMillis()) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val due = synchronized(this) { load(c).filter { isDue(it, now, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)) } }
        due.forEach { j -> runJob(c, j, now) }
    }

    fun runJob(c: Context, j: Job, now: Long = System.currentTimeMillis()): String {
        val out = ShellRunner.run(c, "cd /workspace && ${j.command}", OrionUbuntu.where(c))
        val failed = out.startsWith("error") || Regex("(?m)^exit=(?!0)\\d+").containsMatchIn(out)
        synchronized(this) { save(c, load(c).map { if (it.name == j.name) it.copy(lastRun = now, lastResult = (if (failed) "FAILED " else "ok ") + out.lines().lastOrNull { l -> l.isNotBlank() }.orEmpty().take(80)) else it }) }
        if (failed) OrionReports.report(c, "job ${j.name} failed", out.take(1200))
        return out
    }

    fun runJobNamed(c: Context, name: String): String = load(c).firstOrNull { it.name == name }?.let { runJob(c, it) } ?: "error: no job $name."

    // ---------- entry point ----------
    fun run(c: Context, a: JSONObject): String {
        val action = a.optString("action").trim().lowercase()
        val project = a.optString("project").trim()
        fun guest(script: String?, bad: String): String = if (script == null) "error: $bad" else ShellRunner.run(c, script, OrionUbuntu.where(c))
        return when (action) {
            "", "help" -> "devspace actions: playbook {name?} (engineering playbooks: build-platform, debugging, tdd, review, github-flow, inspect — read the right one before building or debugging), playbook_add {name, description, body} / playbook_import {name, url of a SKILL.md on GitHub} / playbook_remove {name} to extend them yourself, ubuntu_install (full Ubuntu 24.04 arm64 runtime with apt), runtime_status, setup {profile: base|web|data|media|db|net|all}, venv {name, packages?}, run {command, project?, venv?}, new_project {name, kind: python|cli|api|node|web}, " +
                "project_view {project}, backup {project}, service_start {name, command, port?, project?}, service_stop {name}, service_list, service_logs {name, lines?}, service_health {name|port}, " +
                "job_add {name, command, every_minutes|daily_at}, job_list, job_remove {name}, job_run {name}. All of it runs in the real Linux space under /workspace."
            "ubuntu_install" -> OrionUbuntu.install(c)
            "runtime_status" -> "Coding space runs on: " + (if (OrionUbuntu.isInstalled(c)) "Ubuntu 24.04 (arm64, apt)" else "Alpine (apk) — run ubuntu_install for the full Ubuntu runtime") + ". Workspace: /workspace."
            "setup" -> guest(if (OrionUbuntu.isInstalled(c)) OrionUbuntu.setupScript(a.optString("profile").ifBlank { "base" }) else setupScript(a.optString("profile").ifBlank { "base" }), "unknown profile; use base, web, data, media, db, net or all.")
            "venv" -> guest(venvScript(a.optString("name").ifBlank { "main" }, a.optJSONArray("packages")?.let { p -> List(p.length()) { p.getString(it) } } ?: emptyList()), "bad venv name or package.")
            "run" -> guest(runScript(project, a.optString("venv"), a.optString("command")), "run needs a command (and valid project/venv names).")
            "new_project" -> guest(scaffold(a.optString("name"), a.optString("kind").ifBlank { "python" }), "new_project needs a valid name and kind python|cli|api|node|web|android.")
            "playbook" -> OrionPlaybooks.run(c, a.optString("name"))
            "playbook_add" -> OrionPlaybooks.add(c, a.optString("name"), a.optString("description"), a.optString("body"))
            "playbook_import" -> OrionPlaybooks.import(c, a.optString("name"), a.optString("url"))
            "playbook_remove" -> OrionPlaybooks.remove(c, a.optString("name"))
            "project_view" -> guest(viewScript(project.ifBlank { a.optString("name") }), "project_view needs a valid project name.")
            "backup" -> guest(backupScript(project.ifBlank { a.optString("name") }), "backup needs a valid project name.")
            "service_start" -> startService(c, a.optString("name"), a.optString("command"), a.optInt("port", 0), project)
            "service_stop" -> stopService(c, a.optString("name"))
            "service_list" -> listServices()
            "service_logs" -> a.optString("name").let { n -> if (!validName(n)) "error: bad service name." else ShellRunner.run(c, "tail -n ${a.optInt("lines", 40).coerceIn(1, 300)} /workspace/.services/$n.log 2>&1 || echo 'no log yet'", OrionUbuntu.where(c)) }
            "service_health" -> {
                val port = a.optInt("port", 0).takeIf { it > 0 } ?: services[a.optString("name")]?.port ?: 0
                if (port <= 0) "error: give a port or the name of a service that has one." else ShellRunner.run(c, healthScript(port), OrionUbuntu.where(c))
            }
            "job_add" -> addJob(c, a.optString("name"), a.optString("command"), a.optInt("every_minutes", 0), a.optString("daily_at"))
            "job_list" -> listJobs(c)
            "job_remove" -> removeJob(c, a.optString("name"))
            "job_run" -> runJobNamed(c, a.optString("name"))
            else -> "error: unknown devspace action '$action'. Call devspace {action:\"help\"}."
        }
    }
}
