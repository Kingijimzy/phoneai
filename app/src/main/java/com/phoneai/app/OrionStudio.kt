package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Code Studio: the professional development layer on top of the Ubuntu/Alpine coding space.
 * Language intelligence (LSP servers, diagnostics, completion), formatting, debugging, Git, build/test
 * workflows, live preview, toolchain management and ONE shared registry for plugins, apps, extensions
 * and MCP servers. Everything is a script that runs in the real Linux runtime, so Orion drives it from chat
 * and the Studio page shows the same state.
 */
internal object OrionStudio {
    private val safePath = Regex("^[A-Za-z0-9._/-]{1,120}$")
    private val safeRef = Regex("^[A-Za-z0-9._/@:+~^-]{1,120}$")
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun ubuntu(c: Context?) = c != null && OrionUbuntu.isInstalled(c)

    fun validPath(p: String) = p.isNotEmpty() && safePath.matches(p) && !p.contains("..") && !p.startsWith("/")
    fun validRef(r: String) = safeRef.matches(r) && !r.startsWith("-")
    private fun dir(project: String): String? =
        if (project.isBlank()) "/workspace" else if (validPath(project)) "/workspace/$project" else null

    // ---------- toolchains ----------
    val toolchains: Map<String, Pair<String, String>> = mapOf(
        // name -> (apt packages, apk packages)
        "python" to ("python3 python3-pip python3-venv" to "python3 py3-pip py3-virtualenv"),
        "node" to ("nodejs npm" to "nodejs npm"),
        "go" to ("golang-go" to "go"),
        "rust" to ("rustc cargo" to "rust cargo"),
        "java" to ("default-jdk-headless maven gradle" to "openjdk17 maven gradle"),
        "c" to ("build-essential cmake clang clangd gdb" to "build-base cmake clang gdb"),
        "ruby" to ("ruby-full" to "ruby"),
        "php" to ("php-cli composer" to "php83 composer"),
        "lua" to ("lua5.4" to "lua5.4"),
        "sqlite" to ("sqlite3" to "sqlite")
    )

    fun toolchainScript(name: String, ub: Boolean): String? {
        val (apt, apk) = toolchains[name] ?: return null
        return if (ub) "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq 2>&1 | tail -n 2; apt-get install -y -qq --no-install-recommends $apt 2>&1 | tail -n 15; echo TOOLCHAIN_DONE $name"
        else "apk add --no-cache $apk 2>&1 | tail -n 15; echo TOOLCHAIN_DONE $name"
    }

    fun toolchainListScript(): String =
        "for t in python3 pip3 node npm go rustc cargo java mvn gradle gcc clang make cmake git gdb ruby php lua5.4 sqlite3 ffmpeg; do " +
            "p=\$(command -v \$t 2>/dev/null); if [ -n \"\$p\" ]; then v=\$(\$t --version 2>&1 | head -n 1 | cut -c1-60); echo \"✓ \$t  \$v\"; else echo \"· \$t  (not installed)\"; fi; done"

    // ---------- language intelligence ----------
    fun lspInstallScript(lang: String): String? = when (lang) {
        "python" -> "mkdir -p /workspace/.venvs && (test -d /workspace/.venvs/studio || python3 -m venv /workspace/.venvs/studio) && . /workspace/.venvs/studio/bin/activate && pip install -q ruff pyright python-lsp-server jedi pygments pytest 2>&1 | tail -n 5; echo LSP_READY python"
        "typescript", "javascript", "node" -> "npm install -g typescript typescript-language-server prettier eslint 2>&1 | tail -n 5; echo LSP_READY typescript"
        "go" -> "go install golang.org/x/tools/gopls@latest 2>&1 | tail -n 5; echo LSP_READY go"
        "c", "cpp" -> "(apt-get install -y -qq clangd clang-format 2>&1 || apk add --no-cache clang-extra-tools 2>&1) | tail -n 5; echo LSP_READY c"
        "bash", "shell" -> "(apt-get install -y -qq shellcheck 2>&1 || apk add --no-cache shellcheck 2>&1) | tail -n 5; echo LSP_READY bash"
        "rust" -> "(cargo install rust-analyzer 2>&1 || true) | tail -n 3; echo LSP_READY rust"
        else -> null
    }

    /** Auto-detecting diagnostics (lint + type check + syntax) for the project's language. */
    fun diagnosticsScript(project: String): String? {
        val d = dir(project) ?: return null
        return "cd $d 2>/dev/null || { echo no such project; exit 1; }; found=0; " +
            "if ls *.py **/*.py >/dev/null 2>&1; then found=1; echo '== python =='; . /workspace/.venvs/studio/bin/activate 2>/dev/null; (ruff check . 2>&1 || python3 -m compileall -q . 2>&1) | head -n 60; (command -v pyright >/dev/null && pyright . 2>&1 | tail -n 25); fi; " +
            "if [ -f tsconfig.json ]; then found=1; echo '== typescript =='; npx --no-install tsc --noEmit 2>&1 | head -n 40; elif ls *.js >/dev/null 2>&1; then found=1; echo '== javascript =='; for f in *.js; do node --check \"\$f\" 2>&1; done | head -n 30; fi; " +
            "if [ -f go.mod ]; then found=1; echo '== go =='; go vet ./... 2>&1 | head -n 40; fi; " +
            "if [ -f Cargo.toml ]; then found=1; echo '== rust =='; cargo check 2>&1 | tail -n 40; fi; " +
            "if ls *.sh >/dev/null 2>&1 && command -v shellcheck >/dev/null; then found=1; echo '== shell =='; shellcheck *.sh 2>&1 | head -n 40; fi; " +
            "[ \$found = 0 ] && echo 'no recognised source files'; echo DIAGNOSTICS_DONE"
    }

    fun formatScript(project: String): String? {
        val d = dir(project) ?: return null
        return "cd $d 2>/dev/null || exit 1; . /workspace/.venvs/studio/bin/activate 2>/dev/null; " +
            "(ls *.py >/dev/null 2>&1 && ruff format . 2>&1); (command -v prettier >/dev/null && ls *.js *.ts *.json >/dev/null 2>&1 && prettier --write . 2>&1 | tail -n 10); (test -f go.mod && gofmt -l -w . 2>&1); (test -f Cargo.toml && cargo fmt 2>&1); echo FORMAT_DONE"
    }

    /** Python completion via jedi at file:line:col (1-based line, 0-based column). */
    fun completeScript(file: String, line: Int, col: Int): String? {
        if (!validPath(file) || line < 1 || col < 0 || line > 100000 || col > 1000) return null
        return ". /workspace/.venvs/studio/bin/activate 2>/dev/null; python3 - <<'PY'\nimport jedi\ns=jedi.Script(path='/workspace/$file')\nfor c in s.complete($line,$col)[:40]:\n    print(c.name, '-', c.type, '-', (c.description or '')[:60])\nPY"
    }

    fun symbolsScript(file: String): String? =
        if (!validPath(file)) null else "f=/workspace/$file; (command -v ctags >/dev/null && ctags -x \$f | head -n 80) || grep -nE '^\\s*(def|class|function|func|fn|interface|struct)\\s' \$f | head -n 80"

    fun highlightScript(file: String): String? =
        if (!validPath(file)) null else ". /workspace/.venvs/studio/bin/activate 2>/dev/null; (command -v pygmentize >/dev/null && pygmentize -g /workspace/$file) || cat -n /workspace/$file"

    // ---------- debugging ----------
    fun debugScript(file: String, lines: List<Int>, extra: List<String>): String? {
        if (!validPath(file) || lines.any { it < 1 || it > 1_000_000 } || extra.any { it.length > 80 || it.contains('\'') || it.contains('\n') }) return null
        val f = "/workspace/$file"
        val cmds = lines.joinToString(" ") { "-c 'b $it'" } + " -c c " + extra.joinToString(" ") { "-c ${q(it)}" } + " -c bt -c 'info' -c q"
        return when {
            file.endsWith(".py") -> "cd /workspace && python3 -X faulthandler -m pdb ${cmds.replace("-c 'info'", "-c 'args'")} $f 2>&1 | head -n 120"
            file.endsWith(".js") -> "cd /workspace && node --stack-trace-limit=60 $f 2>&1 | head -n 120"
            file.endsWith(".c") || file.endsWith(".cpp") -> "cd /workspace && gcc -g -O0 -o /tmp/orion_dbg $f 2>&1 && gdb -batch " + lines.joinToString(" ") { "-ex 'break $it'" } + " -ex run -ex bt -ex 'info locals' /tmp/orion_dbg 2>&1 | head -n 120"
            else -> null
        }
    }

    // ---------- git ----------
    fun gitScript(op: String, project: String, a: JSONObject): String? {
        val d = dir(project) ?: return null
        val arg = a.optString("ref").trim()
        val msg = a.optString("message").trim()
        val file = a.optString("file").trim()
        val body = when (op) {
            "init" -> "git init -q && git config user.name >/dev/null || git config user.name Orion; git config user.email >/dev/null || git config user.email orion@localhost; git status -sb"
            "status" -> "git status -sb"
            "diff" -> "git diff --stat && git diff | head -n 200"
            "log" -> "git log --oneline --graph --decorate -n 25"
            "branch" -> if (arg.isNotEmpty()) { if (!validRef(arg)) return null; "git switch -c $arg 2>&1 || git switch $arg" } else "git branch -a -vv"
            "checkout" -> if (!validRef(arg)) return null else "git switch $arg 2>&1"
            "add" -> if (file.isEmpty()) "git add -A && git status -sb" else if (!validPath(file)) return null else "git add -- ${q(file)} && git status -sb"
            "commit" -> if (msg.isEmpty() || msg.length > 400) return null else "git config user.name >/dev/null || git config user.name Orion; git config user.email >/dev/null || git config user.email orion@localhost; git add -A && git commit -m ${q(msg)} 2>&1 | tail -n 5"
            "stash" -> "git stash push -u 2>&1 | tail -n 3"
            "stash_pop" -> "git stash pop 2>&1 | tail -n 5"
            "blame" -> if (!validPath(file)) return null else "git blame -s -- ${q(file)} | head -n 80"
            "show" -> "git show --stat ${if (arg.isEmpty()) "HEAD" else if (validRef(arg)) arg else return null} | head -n 80"
            "remote" -> "git remote -v"
            "pull" -> "git pull --ff-only 2>&1 | tail -n 10"
            "push" -> "git push 2>&1 | tail -n 10"
            else -> return null
        }
        return "cd $d 2>/dev/null || { echo 'no such project'; exit 1; }; $body"
    }

    fun cloneScript(url: String, name: String): String? {
        if (!url.startsWith("https://") || url.any { it.isWhitespace() || it == '\'' || it == ';' || it == '&' || it == '|' || it == '$' || it == '`' }) return null
        if (!OrionDevspace.validName(name)) return null
        return "cd /workspace && git clone --depth 50 ${q(url)} ${q(name)} 2>&1 | tail -n 5; ls /workspace/$name | head -n 20"
    }

    // ---------- build / test / preview ----------
    private fun detect(kind: String): String = when (kind) {
        "build" -> "if [ -f Makefile ]; then make 2>&1 | tail -n 40; elif [ -f package.json ]; then (npm install --no-audit --no-fund 2>&1 | tail -n 5; npm run build --if-present 2>&1 | tail -n 40); " +
            "elif [ -f Cargo.toml ]; then cargo build 2>&1 | tail -n 40; elif [ -f go.mod ]; then go build ./... 2>&1 | tail -n 40; elif [ -f pom.xml ]; then mvn -q -B package 2>&1 | tail -n 40; " +
            "elif [ -f build.gradle -o -f build.gradle.kts ]; then gradle build --no-daemon 2>&1 | tail -n 40; elif [ -f CMakeLists.txt ]; then cmake -S . -B build && cmake --build build 2>&1 | tail -n 40; " +
            "elif [ -f pyproject.toml -o -f setup.py ]; then pip install -q build 2>&1 | tail -n 2; python3 -m build 2>&1 | tail -n 30; else echo 'no build system detected (Makefile, package.json, Cargo.toml, go.mod, pom.xml, gradle, CMake, pyproject)'; fi"
        else -> ". /workspace/.venvs/studio/bin/activate 2>/dev/null; if [ -f package.json ]; then npm test --silent 2>&1 | tail -n 50; elif [ -f Cargo.toml ]; then cargo test 2>&1 | tail -n 50; elif [ -f go.mod ]; then go test ./... 2>&1 | tail -n 50; " +
            "elif [ -f pom.xml ]; then mvn -q -B test 2>&1 | tail -n 50; elif ls test*.py tests >/dev/null 2>&1; then (command -v pytest >/dev/null || pip install -q pytest 2>&1 | tail -n 1); python3 -m pytest -q 2>&1 | tail -n 50; elif [ -f Makefile ]; then make test 2>&1 | tail -n 50; else echo 'no tests detected'; fi"
    }

    fun buildScript(project: String, kind: String, target: String): String? {
        val d = dir(project) ?: return null
        if (target.isNotEmpty() && !validRef(target)) return null
        val inner = if (target.isNotEmpty() && kind == "build") "make $target 2>&1 | tail -n 40" else detect(kind)
        return "cd $d 2>/dev/null || { echo 'no such project'; exit 1; }; $inner; echo \"${kind.uppercase()}_EXIT=\$?\""
    }

    /** Preview: a supervised dev server; the URL is reachable from Orion's browser at 127.0.0.1:<port>. */
    fun previewCommand(project: String, port: Int, custom: String): String? {
        if (port !in 1024..65535) return null
        if (custom.isNotBlank()) return custom.takeIf { it.length <= 300 }
        val d = dir(project) ?: return null
        return "cd $d && if [ -f package.json ] && grep -q '\"dev\"' package.json; then npm run dev -- --port $port --host 127.0.0.1; elif [ -f package.json ] && grep -q '\"start\"' package.json; then PORT=$port npm start; " +
            "elif [ -f app.py ]; then PORT=$port python3 app.py; elif [ -f main.py ] && grep -q uvicorn main.py; then python3 -m uvicorn main:app --host 127.0.0.1 --port $port; else python3 -m http.server $port --bind 127.0.0.1; fi"
    }

    // ---------- unified registry (plugins, apps, extensions, MCP servers) ----------
    enum class Kind { PLUGIN, APP, EXTENSION, MCP }
    data class Entry(val id: String, val kind: Kind, val name: String, val about: String, val install: String, val run: String = "")

    val catalog: List<Entry> = listOf(
        Entry("mcp-filesystem", Kind.MCP, "Filesystem MCP", "Read/write files in /workspace for any MCP client", "npm install -g @modelcontextprotocol/server-filesystem", "mcp-server-filesystem /workspace"),
        Entry("mcp-memory", Kind.MCP, "Memory MCP", "Knowledge-graph memory server", "npm install -g @modelcontextprotocol/server-memory", "mcp-server-memory"),
        Entry("mcp-fetch", Kind.MCP, "Fetch MCP", "Fetch web pages as markdown", "pip install -q mcp-server-fetch", "python3 -m mcp_server_fetch"),
        Entry("mcp-git", Kind.MCP, "Git MCP", "Git operations as MCP tools", "pip install -q mcp-server-git", "python3 -m mcp_server_git --repository /workspace"),
        Entry("mcp-sqlite", Kind.MCP, "SQLite MCP", "Query SQLite databases", "pip install -q mcp-server-sqlite", "python3 -m mcp_server_sqlite --db-path /workspace/data.db"),
        Entry("plug-ruff", Kind.PLUGIN, "Ruff", "Python linter and formatter", "pip install -q ruff"),
        Entry("plug-pyright", Kind.PLUGIN, "Pyright", "Python type checker / language server", "pip install -q pyright"),
        Entry("plug-prettier", Kind.PLUGIN, "Prettier", "JS/TS/JSON/CSS formatter", "npm install -g prettier"),
        Entry("plug-eslint", Kind.PLUGIN, "ESLint", "JavaScript linter", "npm install -g eslint"),
        Entry("plug-tsserver", Kind.PLUGIN, "TypeScript language server", "Completion, hover, go-to-definition for TS/JS", "npm install -g typescript typescript-language-server"),
        Entry("ext-httpie", Kind.EXTENSION, "HTTPie", "Friendly HTTP client for testing APIs", "pip install -q httpie"),
        Entry("ext-jupyter", Kind.EXTENSION, "JupyterLab", "Notebooks served locally", "pip install -q jupyterlab", "jupyter lab --ip 127.0.0.1 --port 8899 --no-browser --allow-root"),
        Entry("ext-code-server", Kind.EXTENSION, "code-server", "VS Code in the browser (needs Ubuntu)", "npm install -g code-server", "code-server --bind-addr 127.0.0.1:8890 --auth none /workspace"),
        Entry("app-datasette", Kind.APP, "Datasette", "Explore SQLite data in a web UI", "pip install -q datasette", "datasette /workspace/data.db -p 8001"),
        Entry("app-mkdocs", Kind.APP, "MkDocs", "Static documentation site", "pip install -q mkdocs", "mkdocs serve -a 127.0.0.1:8002"),
        Entry("app-streamlit", Kind.APP, "Streamlit", "Data apps in Python", "pip install -q streamlit", "streamlit run /workspace/app.py --server.port 8501 --server.address 127.0.0.1")
    )

    private const val PREFS = "orion_registry_v1"
    private fun installed(c: Context): MutableMap<String, JSONObject> {
        val out = linkedMapOf<String, JSONObject>()
        runCatching {
            val arr = JSONArray(c.getSharedPreferences(PREFS, 0).getString("items", "[]"))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { out[it.getString("id")] = it }
        }
        return out
    }
    private fun saveAll(c: Context, m: Map<String, JSONObject>) {
        c.getSharedPreferences(PREFS, 0).edit().putString("items", JSONArray(m.values.toList()).toString()).apply()
    }

    fun registryList(c: Context, kind: String = ""): String {
        val have = installed(c)
        val all = catalog + have.values.filter { j -> catalog.none { it.id == j.getString("id") } }.map {
            Entry(it.getString("id"), runCatching { Kind.valueOf(it.optString("kind")) }.getOrDefault(Kind.PLUGIN), it.optString("name"), it.optString("about"), it.optString("install"), it.optString("run"))
        }
        val rows = all.filter { kind.isBlank() || it.kind.name.equals(kind, true) }
        if (rows.isEmpty()) return "Registry is empty for '$kind'. Kinds: plugin, app, extension, mcp."
        return rows.joinToString("\n") { e -> (if (have.containsKey(e.id)) "✓ " else "· ") + "${e.id} [${e.kind.name.lowercase()}] ${e.name} — ${e.about}" }
    }

    fun registrySearch(query: String): String? {
        val t = query.trim().lowercase()
        if (t.isBlank()) return null
        val local = catalog.filter { (it.id + it.name + it.about).lowercase().contains(t) }
        return local.joinToString("\n") { "${it.id} [${it.kind.name.lowercase()}] ${it.name} — ${it.about}" }
    }

    fun npmSearchScript(query: String): String? =
        if (!Regex("^[A-Za-z0-9 _.@/-]{1,60}$").matches(query)) null else "npm search --json ${q(query)} 2>/dev/null | head -c 3000 || echo 'search unavailable'"

    /** Install command for a catalogue id or a validated custom package. */
    fun installCommand(id: String, kind: String, source: String): Pair<Entry, String>? {
        catalog.firstOrNull { it.id == id }?.let { return it to it.install }
        val k = runCatching { Kind.valueOf(kind.uppercase()) }.getOrNull() ?: return null
        if (!Regex("^(npm|pip):[A-Za-z0-9@/._-]{1,80}$").matches(source) || source.contains("..")) return null
        val cmd = if (source.startsWith("npm:")) "npm install -g ${source.removePrefix("npm:")}" else "pip install -q ${source.removePrefix("pip:")}"
        val eid = id.ifBlank { source.substringAfter(':').replace(Regex("[^A-Za-z0-9._-]"), "-") }
        if (!OrionDevspace.validName(eid)) return null
        return Entry(eid, k, eid, "custom ${source}", cmd) to cmd
    }

    fun registryInstall(c: Context, a: JSONObject): String {
        val (entry, cmd) = installCommand(a.optString("id").trim(), a.optString("kind"), a.optString("source").trim())
            ?: return "error: unknown registry id; give a catalogue id or {kind, source:\"npm:<pkg>\"|\"pip:<pkg>\", id}."
        val out = ShellRunner.run(c, ". /workspace/.venvs/studio/bin/activate 2>/dev/null; ($cmd) > /tmp/orion_reg.log 2>&1; rc=\$?; tail -n 12 /tmp/orion_reg.log; echo INSTALL_EXIT=\$rc", OrionUbuntu.where(c))
        if (!out.contains("INSTALL_EXIT=0")) return "Install did not finish cleanly:\n$out"
        val m = installed(c)
        m[entry.id] = JSONObject().put("id", entry.id).put("kind", entry.kind.name).put("name", entry.name).put("about", entry.about).put("install", entry.install).put("run", entry.run).put("at", System.currentTimeMillis())
        saveAll(c, m)
        return "Installed ${entry.name} (${entry.kind.name.lowercase()})." + if (entry.run.isNotBlank()) " Start it with studio {action:\"registry_run\", id:\"${entry.id}\"}." else ""
    }

    fun registryRemove(c: Context, id: String): String {
        val m = installed(c)
        if (m.remove(id) == null) return "error: $id is not installed."
        saveAll(c, m)
        return "Removed $id from the registry (packages stay in the runtime until you uninstall them)."
    }

    fun registryRun(c: Context, id: String, port: Int): String {
        val j = installed(c)[id] ?: return "error: install $id first."
        val run = j.optString("run")
        if (run.isBlank()) return "$id has nothing to run; it is a tool used from the shell."
        return OrionDevspace.startService(c, id.take(40), run, port, "")
    }

    // ---------- unified runtime overview (what Orion and the owner both see) ----------
    fun overview(c: Context): String = buildString {
        appendLine("RUNTIME: " + if (ubuntu(c)) "Ubuntu 24.04 (apt)" else "Alpine (apk)")
        val steps = OrionRunLog.currentRun()
        val (finished, total, failed) = OrionRunLog.progress(steps)
        appendLine("CURRENT RUN: $finished of $total steps finished · $failed failed")
        val g = runCatching { OrionObjectiveGraph.load(c) }.getOrNull()
        if (g != null) {
            appendLine("OBJECTIVE: ${g.objective}")
            g.ordered().forEach { appendLine("  [${it.status.name}] ${it.id} ${it.title}") }
        } else appendLine("OBJECTIVE: none active")
        appendLine("WORKERS:"); appendLine(OrionWorkers.list(c).prependIndent("  "))
        appendLine("SERVICES:"); appendLine(OrionDevspace.listServices().prependIndent("  "))
        appendLine("JOBS:"); appendLine(OrionDevspace.listJobs(c).prependIndent("  "))
        val reg = installed(c)
        appendLine("REGISTRY: ${reg.size} installed (" + reg.values.groupBy { it.optString("kind") }.entries.joinToString { "${it.key.lowercase()} ${it.value.size}" } + ")")
    }

    // ---------- dispatcher ----------
    fun run(c: Context, a: JSONObject): String {
        val action = a.optString("action").trim().lowercase()
        val project = a.optString("project").trim()
        val where = OrionUbuntu.where(c)
        fun guest(s: String?, bad: String): String = if (s == null) "error: $bad" else ShellRunner.run(c, s, where)
        return when (action) {
            "", "help" -> "studio actions: status (everything running: objective, workers, services, jobs, registry), toolchain_list, toolchain_install {name: ${toolchains.keys.joinToString("|")}}, " +
                "lsp_install {lang: python|typescript|go|c|bash|rust}, diagnostics {project}, format {project}, complete {file,line,col}, symbols {file}, highlight {file}, " +
                "debug {file, breakpoints?:[lines], commands?:[pdb cmds]}, git {op: init|status|diff|log|branch|checkout|add|commit|stash|stash_pop|blame|show|remote|pull|push, project, message?, ref?, file?}, git_clone {url,name}, " +
                "build {project,target?}, test {project}, preview {project,port?,command?}, preview_stop {name}, " +
                "registry_list {kind?}, registry_search {query}, registry_npm {query}, registry_install {id | kind+source}, registry_remove {id}, registry_run {id,port?}. Projects live under /workspace."
            "status" -> overview(c)
            "toolchain_list" -> guest(toolchainListScript(), "")
            "toolchain_install" -> guest(toolchainScript(a.optString("name").lowercase(), ubuntu(c)), "unknown toolchain; use ${toolchains.keys.joinToString(", ")}.")
            "lsp_install" -> guest(lspInstallScript(a.optString("lang").lowercase()), "unknown language; use python, typescript, go, c, bash or rust.")
            "diagnostics" -> guest(diagnosticsScript(project), "bad project path.")
            "format" -> guest(formatScript(project), "bad project path.")
            "complete" -> guest(completeScript(a.optString("file"), a.optInt("line", 1), a.optInt("col", 0)), "complete needs a relative file, line and col.")
            "symbols" -> guest(symbolsScript(a.optString("file")), "bad file path.")
            "highlight" -> guest(highlightScript(a.optString("file")), "bad file path.")
            "debug" -> guest(debugScript(a.optString("file"),
                a.optJSONArray("breakpoints")?.let { b -> List(b.length()) { b.optInt(it) } } ?: emptyList(),
                a.optJSONArray("commands")?.let { b -> List(b.length()) { b.optString(it) } } ?: emptyList()), "debug supports .py, .js, .c/.cpp with valid breakpoints.")
            "git" -> guest(gitScript(a.optString("op").lowercase(), project, a), "bad git op or arguments.")
            "git_clone" -> guest(cloneScript(a.optString("url"), a.optString("name")), "clone needs an https url and a safe name.")
            "build" -> guest(buildScript(project, "build", a.optString("target").trim()), "bad project or target.")
            "test" -> guest(buildScript(project, "test", ""), "bad project path.")
            "preview" -> {
                val port = a.optInt("port", 8080)
                val cmd = previewCommand(project, port, a.optString("command"))
                if (cmd == null) "error: bad preview arguments (port 1024-65535)."
                else OrionDevspace.startService(c, "preview-" + (project.ifBlank { "root" }).replace(Regex("[^A-Za-z0-9._-]"), "-").take(30), cmd, port, "") +
                    "\nPreview: http://127.0.0.1:$port/ — open it with open_url."
            }
            "preview_stop" -> OrionDevspace.stopService(c, a.optString("name"))
            "registry_list" -> registryList(c, a.optString("kind"))
            "registry_search" -> registrySearch(a.optString("query")) ?: "error: give a query."
            "registry_npm" -> guest(npmSearchScript(a.optString("query")), "bad query.")
            "registry_install" -> registryInstall(c, a)
            "registry_remove" -> registryRemove(c, a.optString("id"))
            "registry_run" -> registryRun(c, a.optString("id"), a.optInt("port", 0))
            else -> "error: unknown studio action '$action'. Call studio {action:\"help\"}."
        }
    }
}
