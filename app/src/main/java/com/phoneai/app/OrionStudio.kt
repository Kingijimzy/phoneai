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
    /** POSIX `.` of a missing file aborts a non-interactive sh (dash/ash), so activation must be guarded. */
    const val VENV = "{ [ -f /workspace/.venvs/studio/bin/activate ] && . /workspace/.venvs/studio/bin/activate; } 2>/dev/null"
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

    /** A previously interrupted apt/dpkg run leaves the package database half-configured; heal it before any apt call. */
    const val APT_HEAL = "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a 2>&1 | tail -n 3; apt-get -f install -y -qq 2>&1 | tail -n 3"

    fun toolchainScript(name: String, ub: Boolean): String? {
        val (apt, apk) = toolchains[name] ?: return null
        return if (ub) "$APT_HEAL; apt-get update 2>&1 | grep -E '^(Get:|Fetched|E:)'; apt-get install -y --no-install-recommends $apt 2>&1; echo TOOLCHAIN_DONE $name"
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
            "if ls *.py **/*.py >/dev/null 2>&1; then found=1; echo '== python =='; $VENV; (ruff check . 2>&1 || python3 -m compileall -q . 2>&1) | head -n 60; (command -v pyright >/dev/null && pyright . 2>&1 | tail -n 25); fi; " +
            "if [ -f tsconfig.json ]; then found=1; echo '== typescript =='; npx --no-install tsc --noEmit 2>&1 | head -n 40; elif ls *.js >/dev/null 2>&1; then found=1; echo '== javascript =='; for f in *.js; do node --check \"\$f\" 2>&1; done | head -n 30; fi; " +
            "if [ -f go.mod ]; then found=1; echo '== go =='; go vet ./... 2>&1 | head -n 40; fi; " +
            "if [ -f Cargo.toml ]; then found=1; echo '== rust =='; cargo check 2>&1 | tail -n 40; fi; " +
            "if ls *.sh >/dev/null 2>&1 && command -v shellcheck >/dev/null; then found=1; echo '== shell =='; shellcheck *.sh 2>&1 | head -n 40; fi; " +
            "[ \$found = 0 ] && echo 'no recognised source files'; echo DIAGNOSTICS_DONE"
    }

    fun formatScript(project: String): String? {
        val d = dir(project) ?: return null
        return "cd $d 2>/dev/null || exit 1; $VENV; " +
            "(ls *.py >/dev/null 2>&1 && ruff format . 2>&1); (command -v prettier >/dev/null && ls *.js *.ts *.json >/dev/null 2>&1 && prettier --write . 2>&1 | tail -n 10); (test -f go.mod && gofmt -l -w . 2>&1); (test -f Cargo.toml && cargo fmt 2>&1); echo FORMAT_DONE"
    }

    /** Python completion via jedi at file:line:col (1-based line, 0-based column). */
    fun completeScript(file: String, line: Int, col: Int): String? {
        if (!validPath(file) || line < 1 || col < 0 || line > 100000 || col > 1000) return null
        return "$VENV; python3 - <<'PY'\nimport jedi\ns=jedi.Script(path='/workspace/$file')\nfor c in s.complete($line,$col)[:40]:\n    print(c.name, '-', c.type, '-', (c.description or '')[:60])\nPY"
    }

    fun symbolsScript(file: String): String? =
        if (!validPath(file)) null else "f=/workspace/$file; (command -v ctags >/dev/null && ctags -x \$f | head -n 80) || grep -nE '^\\s*(def|class|function|func|fn|interface|struct)\\s' \$f | head -n 80"

    fun highlightScript(file: String): String? =
        if (!validPath(file)) null else "$VENV; (command -v pygmentize >/dev/null && pygmentize -g /workspace/$file) || cat -n /workspace/$file"

    // ---------- debugging ----------
    fun debugScript(file: String, lines: List<Int>, extra: List<String>): String? {
        if (!validPath(file) || lines.any { it < 1 || it > 1_000_000 } || extra.any { it.length > 80 || it.contains('\'') || it.contains('\n') }) return null
        val f = "/workspace/$file"
        val cmds = lines.joinToString(" ") { "-c 'b $it'" } + " -c c " + extra.joinToString(" ") { "-c ${q(it)}" } + " -c 'pp locals()' -c bt -c 'info' -c q"
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
        else -> "$VENV; if [ -f package.json ]; then npm test --silent 2>&1 | tail -n 50; elif [ -f Cargo.toml ]; then cargo test 2>&1 | tail -n 50; elif [ -f go.mod ]; then go test ./... 2>&1 | tail -n 50; " +
            "elif [ -f pom.xml ]; then mvn -q -B test 2>&1 | tail -n 50; elif ls test*.py tests >/dev/null 2>&1; then (command -v pytest >/dev/null || pip install -q pytest 2>&1 | tail -n 1); python3 -m pytest -q 2>&1 | tail -n 50; elif [ -f Makefile ]; then make test 2>&1 | tail -n 50; else echo 'no tests detected'; fi"
    }

    fun buildScript(project: String, kind: String, target: String): String? {
        val d = dir(project) ?: return null
        if (target.isNotEmpty() && !validRef(target)) return null
        val inner = (if (target.isNotEmpty() && kind == "build") "make $target 2>&1" else detect(kind)).replace(Regex(" 2>&1 \\| tail -n \\d+"), " 2>&1")
        return "cd $d 2>/dev/null || { echo 'no such project'; exit 1; }; ( $inner ) > /tmp/orion_$kind.log 2>&1; rc=\$?; tail -n 80 /tmp/orion_$kind.log; echo \"${kind.uppercase()}_EXIT=\$rc\""
    }

    /** Preview: a supervised dev server; the URL is reachable from Orion's browser at 127.0.0.1:<port>. */
    fun previewCommand(project: String, port: Int, custom: String): String? {
        if (port !in 1024..65535) return null
        if (custom.isNotBlank()) return custom.takeIf { it.length <= 300 }
        val d = dir(project) ?: return null
        return "cd $d && if [ -f package.json ] && grep -q '\"dev\"' package.json; then npm run dev -- --port $port --host 127.0.0.1; elif [ -f package.json ] && grep -q '\"start\"' package.json; then PORT=$port npm start; " +
            "elif [ -f app.py ]; then PORT=$port python3 app.py; elif [ -f main.py ] && grep -q uvicorn main.py; then python3 -m uvicorn main:app --host 127.0.0.1 --port $port; else python3 -m http.server $port --bind 127.0.0.1; fi"
    }

    // ---------- per-file intelligence for the editor ----------
    fun fileDiagnosticsScript(file: String): String? {
        if (!validPath(file)) return null
        val f = "/workspace/$file"
        val ext = file.substringAfterLast('.', "").lowercase()
        val body = when (ext) {
            "py" -> "$VENV; (ruff check --output-format concise $f 2>&1 || python3 -m py_compile $f 2>&1); (command -v pyright >/dev/null && pyright $f 2>&1 | grep -E '(error|warning)')"
            "js", "mjs", "cjs" -> "node --check $f 2>&1 | head -n 12; (command -v eslint >/dev/null && eslint -f unix $f 2>&1 | head -n 40)"
            "ts", "tsx" -> "cd /workspace && (npx --no-install tsc --noEmit --pretty false $f 2>&1 | head -n 40)"
            "go" -> "cd \$(dirname $f) && go vet ./... 2>&1 | head -n 40"
            "rs" -> "cd /workspace && cargo check --message-format short 2>&1 | head -n 40"
            "c", "h" -> "gcc -fsyntax-only -Wall $f 2>&1 | head -n 40"
            "cpp", "cc", "hpp" -> "g++ -fsyntax-only -Wall $f 2>&1 | head -n 40"
            "sh", "bash" -> "(command -v shellcheck >/dev/null && shellcheck -f gcc $f 2>&1 | head -n 40); sh -n $f 2>&1 | head -n 10"
            "json" -> "python3 -m json.tool $f >/dev/null 2>&1 || python3 -c \"import json,sys;json.load(open('$f'))\" 2>&1 | tail -n 3"
            else -> return null
        }
        return "$body; echo DIAG_DONE"
    }

    /** Hover: signature + docstring + definition location for the symbol at (line, col), via jedi. */
    fun hoverScript(file: String, line: Int, col: Int): String? {
        if (!validPath(file) || !file.endsWith(".py") || line < 1 || col < 0 || line > 100000 || col > 1000) return null
        return "$VENV; python3 - <<'PY'\nimport jedi\ns=jedi.Script(path='/workspace/$file')\nn=s.help($line,$col)\nif not n:\n    print('no information here')\nfor d in n[:2]:\n    print(d.full_name or d.name, '-', d.type)\n    for sig in d.get_signatures()[:2]:\n        print('  ', sig.to_string())\n    print((d.docstring() or '')[:600])\nfor d in s.goto($line,$col,follow_imports=True)[:2]:\n    if d.module_path:\n        print('defined at', d.module_path, 'line', d.line)\nPY"
    }

    // ---------- real MCP client over stdio (inside the Linux runtime) ----------
    /** Speaks JSON-RPC MCP to a stdio server: initialize -> method (tools/list | tools/call). Output is the JSON result. */
    fun mcpScript(runCommand: String, method: String, params: JSONObject): String? {
        if (runCommand.isBlank() || runCommand.length > 400 || runCommand.contains('\n')) return null
        if (method != "tools/list" && method != "tools/call") return null
        val py = """
import json, os, subprocess, select, time, sys
p = subprocess.Popen(os.environ['ORION_MCP_CMD'], shell=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
def send(o):
    p.stdin.write(json.dumps(o) + '\n'); p.stdin.flush()
def recv(i, t=90):
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([p.stdout], [], [], 1)
        if not r: continue
        line = p.stdout.readline()
        if not line: break
        try: m = json.loads(line)
        except Exception: continue
        if m.get('id') == i: return m
    return {'error': 'timeout waiting for MCP server'}
send({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'orion', 'version': '1'}}})
r = recv(1)
if 'error' in r and 'result' not in r:
    print('MCP initialize failed:', r['error']); p.kill(); sys.exit(1)
send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
send({'jsonrpc': '2.0', 'id': 2, 'method': os.environ['ORION_MCP_METHOD'], 'params': json.loads(os.environ['ORION_MCP_PARAMS'])})
r = recv(2)
res = r.get('result', r)
if os.environ['ORION_MCP_METHOD'] == 'tools/list':
    for t in res.get('tools', []):
        props = list((t.get('inputSchema') or {}).get('properties', {}).keys())
        print(t.get('name'), '-', (t.get('description') or '')[:120], props)
else:
    for c in res.get('content', []):
        print(c.get('text', json.dumps(c))[:6000])
    if res.get('isError'): print('(server reported an error)')
p.kill()
""".trimIndent()
        return "$VENV; ORION_MCP_CMD=${q(runCommand)} ORION_MCP_METHOD=${q(method)} ORION_MCP_PARAMS=${q(params.toString())} python3 - <<'ORION_PY'\n$py\nORION_PY"
    }

    // ---------- live discovery of the public ecosystems (Open VSX, npm, MCP registry) ----------
    private fun httpGet(url: String): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "Orion-Studio")
        }
        return try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText().take(400_000) }
        } finally { conn.disconnect() }
    }

    fun discoverUrl(source: String, query: String): String? {
        val e = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (query.isBlank() || query.length > 80) return null
        return when (source) {
            "openvsx", "vscode" -> "https://open-vsx.org/api/-/search?query=$e&size=10"
            "npm" -> "https://registry.npmjs.org/-/v1/search?text=$e&size=10"
            "mcp" -> "https://registry.modelcontextprotocol.io/v0/servers?search=$e&limit=10"
            else -> null
        }
    }

    fun parseDiscovery(source: String, body: String): List<String> = runCatching {
        val j = JSONObject(body)
        when (source) {
            "openvsx", "vscode" -> j.optJSONArray("extensions")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { x ->
                "${x.optString("namespace")}.${x.optString("name")} v${x.optString("version")} — ${x.optString("displayName", x.optString("name"))}: ${x.optString("description").take(90)} (${x.optInt("downloadCount")} downloads)" } } }
            "npm" -> j.optJSONArray("objects")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).getJSONObject("package").let { x ->
                "npm:${x.optString("name")} v${x.optString("version")} — ${x.optString("description").take(100)}" } } }
            "mcp" -> j.optJSONArray("servers")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { x ->
                val pk = x.optJSONArray("packages")?.optJSONObject(0)
                val how = if (pk != null) " [${pk.optString("registry_type", pk.optString("registryType"))}:${pk.optString("identifier", pk.optString("name"))}]" else ""
                "${x.optString("name")}$how — ${x.optString("description").take(100)}" } } }
            else -> null
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun discover(source: String, query: String): String {
        val url = discoverUrl(source.lowercase(), query) ?: return "error: discover needs source openvsx|npm|mcp and a query."
        return runCatching {
            val rows = parseDiscovery(source.lowercase(), httpGet(url))
            if (rows.isEmpty()) "No results for '$query' on ${source.lowercase()}."
            else rows.joinToString("\n") + when (source.lowercase()) {
                "openvsx", "vscode" -> "\nInstall with studio {action:\"registry_install_vsx\", id:\"<namespace.name>\"}."
                "npm" -> "\nInstall with studio {action:\"registry_install\", kind:\"plugin\", source:\"npm:<package>\"}."
                else -> "\nInstall a package-backed MCP server with studio {action:\"registry_install\", kind:\"mcp\", source:\"npm:<package>\", run:\"npx -y <package>\"}."
            }
        }.getOrElse { "error: could not reach ${source.lowercase()} (${it.message}). Check the connection and retry." }
    }

    private val vsxId = Regex("^[A-Za-z0-9_-]{1,60}\\.[A-Za-z0-9_.-]{1,80}$")
    fun vsxInstallScript(id: String): String? {
        if (!vsxId.matches(id) || id.contains("..")) return null
        val ns = id.substringBefore('.'); val name = id.substringAfter('.')
        return "set -e; mkdir -p /workspace/.vsx/tmp && cd /workspace/.vsx/tmp && rm -rf x && mkdir x && cd x && " +
            "curl -fsSL https://open-vsx.org/api/$ns/$name/latest -o meta.json && " +
            "url=\$(python3 -c \"import json;print(json.load(open('meta.json'))['files']['download'])\") && " +
            "curl -fsSL \"\$url\" -o ext.vsix && unzip -oq ext.vsix 'extension/*' -d out && rm -rf /workspace/.vsx/$id && mv out/extension /workspace/.vsx/$id && " +
            "echo VSX_INSTALLED $id; ls /workspace/.vsx/$id | head -n 8"
    }

    // ---------- real test/build result reporting ----------
    data class Report(val ok: Boolean, val summary: String)

    /** Reads actual results out of pytest / jest / go / cargo / make output and the real exit code. */
    fun report(output: String, kind: String): Report {
        val exit = Regex("${kind.uppercase()}_EXIT=(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        val parts = ArrayList<String>()
        Regex("(\\d+) passed").find(output)?.let { parts += "✓ ${it.groupValues[1]} passed" }
        Regex("(\\d+) failed").find(output)?.let { parts += "✗ ${it.groupValues[1]} failed" }
        Regex("Tests:\\s+(?:(\\d+) failed, )?(\\d+) passed").find(output)?.let { m ->
            if (parts.isEmpty()) { parts += "✓ ${m.groupValues[2]} passed"; if (m.groupValues[1].isNotEmpty()) parts += "✗ ${m.groupValues[1]} failed" }
        }
        Regex("(?m)^ok\\s+\\S+").findAll(output).count().takeIf { it > 0 && parts.isEmpty() }?.let { parts += "✓ $it package${if (it == 1) "" else "s"} ok" }
        Regex("(?m)^FAIL\\s+\\S+").findAll(output).count().takeIf { it > 0 }?.let { parts += "✗ $it package${if (it == 1) "" else "s"} failed" }
        Regex("test result: (ok|FAILED)\\. (\\d+) passed; (\\d+) failed").find(output)?.let { if (parts.isEmpty()) parts += "✓ ${it.groupValues[2]} passed · ✗ ${it.groupValues[3]} failed" }
        val ok = exit == 0
        val head = if (exit == null) "no exit status" else if (ok) "succeeded" else "failed (exit $exit)"
        return Report(ok, (kind.replaceFirstChar { it.uppercase() }) + " " + head + if (parts.isEmpty()) "" else " — " + parts.joinToString(" · "))
    }

    // ---------- runtime selection ----------
    fun runtimeStatus(c: Context): String {
        val ub = OrionUbuntu.isInstalled(c)
        return "Active: ${OrionUbuntu.label(c)} (choice: ${OrionUbuntu.choice(c)})\n" +
            "Ubuntu 24.04 (primary): " + (if (ub) "installed" else "not installed — studio {action:\"ubuntu_install\"}") + "\n" +
            "Alpine (alternative): " + (if (LinuxRuntimeInstaller(c.applicationContext).isReady(LinuxRuntimePolicy.productionManifest)) "installed" else "not installed (installs on first use)")
    }

    // ---------- package manager (apt, apk, pip, npm) ----------
    private val pkgName = Regex("^[A-Za-z0-9][A-Za-z0-9._+@/:=~-]{0,79}$")
    fun packageScript(manager: String, op: String, names: List<String>, ub: Boolean): String? {
        if (names.size > 20 || names.any { !pkgName.matches(it) }) return null
        val n = names.joinToString(" ") { q(it) }
        val mgr = if (manager == "auto" || manager.isBlank()) (if (ub) "apt" else "apk") else manager
        if ((mgr == "apt" && !ub) || (mgr == "apk" && ub)) return "echo ${q("$mgr is not the package manager of the active runtime (" + (if (ub) "Ubuntu uses apt" else "Alpine uses apk") + ")")}; exit 1"
        val need = if (op == "install" || op == "remove") names.isNotEmpty() else if (op == "search") names.size == 1 else true
        if (!need) return null
        val body = when (mgr to op) {
            "apt" to "list" -> "dpkg-query -W -f='\${binary:Package} \${Version}\\n' | head -n 300"
            "apt" to "search" -> "apt-cache search $n | head -n 40"
            "apt" to "install" -> "$APT_HEAL; apt-get update 2>&1 | grep -E '^(Get:|Fetched|E:)'; apt-get install -y --no-install-recommends $n 2>&1"
            "apt" to "remove" -> "export DEBIAN_FRONTEND=noninteractive; apt-get remove -y -qq $n 2>&1 | tail -n 15"
            "apt" to "update" -> "export DEBIAN_FRONTEND=noninteractive; apt-get update 2>&1 | tail -n 4; apt-get upgrade -y -qq 2>&1 | tail -n 15"
            "apk" to "list" -> "apk info -v | head -n 300"
            "apk" to "search" -> "apk search $n | head -n 40"
            "apk" to "install" -> "apk add --no-cache $n 2>&1 | tail -n 20"
            "apk" to "remove" -> "apk del $n 2>&1 | tail -n 15"
            "apk" to "update" -> "apk update 2>&1 | tail -n 3; apk upgrade 2>&1 | tail -n 15"
            "pip" to "list" -> "pip list --format=freeze 2>&1 | head -n 300"
            "pip" to "search" -> "pip index versions $n 2>&1 | head -n 6"
            "pip" to "install" -> "pip install -q $n 2>&1 | tail -n 20"
            "pip" to "remove" -> "pip uninstall -y $n 2>&1 | tail -n 10"
            "pip" to "update" -> "pip install -q -U ${if (names.isEmpty()) "pip" else n} 2>&1 | tail -n 15"
            "npm" to "list" -> "npm ls -g --depth=0 2>&1 | head -n 100"
            "npm" to "search" -> "npm search --parseable $n 2>&1 | head -n 25"
            "npm" to "install" -> "npm install -g --no-audit --no-fund $n 2>&1 | tail -n 15"
            "npm" to "remove" -> "npm uninstall -g $n 2>&1 | tail -n 10"
            "npm" to "update" -> "npm update -g 2>&1 | tail -n 15"
            else -> return null
        }
        val pre = if (mgr == "pip") installScriptPrelude("pip", ub) else if (mgr == "npm") installScriptPrelude("npm", ub) else "true"
        return "$pre; $VENV; $body"
    }

    private fun installScriptPrelude(kind: String, ub: Boolean): String =
        installScript(kind, ub).substringBefore("; $VENV")

    // ---------- recovery: snapshots, rollback, resume ----------
    private val snapName = Regex("^[A-Za-z0-9._-]{1,100}\\.tar\\.gz$")
    fun snapshotScript(project: String, label: String): String? {
        val d = dir(project) ?: return null
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "-").take(30).ifBlank { "manual" }
        val base = (if (project.isBlank()) "workspace" else project.replace('/', '_')) + "-\$(date +%Y%m%d-%H%M%S)-" + safeLabel
        return "mkdir -p /workspace/.snapshots && cd /workspace && tar -czf /workspace/.snapshots/$base.tar.gz " +
            "--exclude=./.snapshots --exclude=./.venvs --exclude=./.vsx --exclude='node_modules' --exclude='__pycache__' " +
            (if (project.isBlank()) "." else "./${project.trim('/')}") + " 2>&1 | tail -n 3; ls -t /workspace/.snapshots | head -n 1; echo SNAPSHOT_DONE; true; " +
            "[ -d $d ] || echo 'note: project folder is empty'"
    }
    fun snapshotListScript(): String = "ls -lht /workspace/.snapshots 2>/dev/null | awk 'NR>1{print \$9, \$5}' | head -n 40 || true; echo SNAPSHOT_LIST_DONE"
    fun restoreScript(name: String): String? =
        if (!snapName.matches(name)) null else "cd /workspace && test -f .snapshots/$name || { echo 'no such snapshot'; exit 1; }; " +
            "tar -czf .snapshots/pre-restore-\$(date +%s).tar.gz --exclude=./.snapshots --exclude=./.venvs --exclude=./.vsx --exclude=node_modules . 2>/dev/null; " +
            "tar -xzf .snapshots/$name -C /workspace && echo RESTORED $name"
    fun rollbackScript(project: String, ref: String): String? {
        val d = dir(project) ?: return null
        if (!validRef(ref)) return null
        return "cd $d 2>/dev/null || exit 1; git stash push -u -m orion-pre-rollback >/dev/null 2>&1; git reset --hard $ref 2>&1 | tail -n 2; echo ROLLED_BACK $ref"
    }

    // ---------- Orion Dev Agent: autonomous coding through the shared worker system ----------
    fun devPrompt(objective: String, project: String, snapshot: String): String =
        "You are Orion Dev Agent working in the Code Studio. Project folder: /workspace/" + project.ifBlank { "(workspace root)" } + ". A recovery snapshot was taken first ($snapshot). " +
            "Work in this loop and do not skip steps: 1 INSPECT the project (studio git status, files, diagnostics). 2 PLAN the smallest correct change. " +
            "3 EDIT files in /workspace. 4 EXECUTE it with real commands (studio build/test, run the app). 5 DEBUG from real errors (studio debug, file_diagnostics). " +
            "6 VERIFY with a passing build/test and say exactly what ran and what it printed. Commit with studio git when it works. " +
            "Never claim success without a real passing result; if blocked, report the exact error. Keep chat replies short and mention file paths instead of pasting code. Objective: " + objective.trim()

    fun devTask(c: Context, objective: String, project: String): String {
        if (objective.trim().length < 8) return "error: dev_task needs a concrete coding objective."
        val svc = PhoneAiService.instance ?: return "error: Orion service is not running; open Orion and retry."
        val snap = if (project.isNotBlank() && !validPath(project)) return "error: bad project path." else snapshotScript(project, "before-dev-agent")?.let { ShellRunner.run(c, it, OrionUbuntu.where(c)).lineSequence().firstOrNull { l -> l.endsWith(".tar.gz") } ?: "snapshot" } ?: "snapshot"
        return OrionWorkers.spawn(svc, devPrompt(objective, project, snap), "Dev: " + objective.trim().take(32))
    }

    fun openPage(c: Context, page: String): String {
        val valid = setOf("editor", "terminal", "runtime", "packages", "extensions", "debug", "build", "git", "agent", "recovery")
        val p = page.ifBlank { "editor" }
        if (p !in valid) return "error: pages are ${valid.joinToString(", ")}."
        return runCatching {
            c.startActivity(android.content.Intent(c, StudioActivity::class.java).putExtra("page", p).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened Code Studio · $p."
        }.getOrElse { "error: could not open Code Studio (${it.javaClass.simpleName})" }
    }

    // ---------- registry lifecycle: enable / disable / update ----------
    fun registrySetEnabled(c: Context, id: String, enabled: Boolean): String {
        val m = installed(c)
        val j = m[id] ?: return "error: $id is not installed."
        j.put("enabled", enabled); saveAll(c, m)
        return (if (enabled) "Enabled " else "Disabled ") + id + "."
    }
    fun isEnabled(c: Context, id: String): Boolean = installed(c)[id]?.optBoolean("enabled", true) ?: false
    fun isInstalledEntry(c: Context, id: String): Boolean = installed(c).containsKey(id)
    fun registryUpdate(c: Context, id: String): String {
        val j = installed(c)[id] ?: return "error: $id is not installed."
        val cmd = j.optString("install")
        if (cmd.isBlank()) return "$id has no package to update (VS Code extensions: reinstall with registry_install_vsx)."
        val upd = cmd.replace("npm install -g ", "npm install -g ").replace("pip install -q ", "pip install -q -U ")
        return OrionProgress.run(c, "Updating", installScript(upd, ubuntu(c)), OrionUbuntu.where(c))
    }
    fun registryEntry(c: Context, id: String): Entry? =
        catalog.firstOrNull { it.id == id } ?: installed(c)[id]?.let {
            Entry(id, runCatching { Kind.valueOf(it.optString("kind")) }.getOrDefault(Kind.PLUGIN), it.optString("name"), it.optString("about"), it.optString("install"), it.optString("run"))
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
    fun installCommand(id: String, kind: String, source: String, run: String = ""): Pair<Entry, String>? {
        if (run.length > 300 || run.contains('\n')) return null
        catalog.firstOrNull { it.id == id }?.let { return it to it.install }
        val k = runCatching { Kind.valueOf(kind.uppercase()) }.getOrNull() ?: return null
        if (!Regex("^(npm|pip):[A-Za-z0-9@/._-]{1,80}$").matches(source) || source.contains("..")) return null
        val cmd = if (source.startsWith("npm:")) "npm install -g ${source.removePrefix("npm:")}" else "pip install -q ${source.removePrefix("pip:")}"
        val eid = id.ifBlank { source.substringAfter(':').replace(Regex("[^A-Za-z0-9._-]"), "-") }
        if (!OrionDevspace.validName(eid)) return null
        return Entry(eid, k, eid, "custom ${source}", cmd, run) to cmd
    }

    /** Makes sure the package manager and the shared venv exist, then installs and reports the real tail of the log. */
    fun installScript(cmd: String, ub: Boolean): String {
        val prelude = when {
            cmd.startsWith("npm") -> "command -v npm >/dev/null 2>&1 || { ${toolchainScript("node", ub)}; command -v npm >/dev/null 2>&1 || " + (if (ub) "apt-get install -y npm 2>&1" else "apk add --no-cache npm 2>&1") + "; }"
            cmd.startsWith("pip") -> "command -v python3 >/dev/null 2>&1 || { ${toolchainScript("python", ub)}; }; " +
                "[ -f /workspace/.venvs/studio/bin/activate ] || { mkdir -p /workspace/.venvs; python3 -m venv /workspace/.venvs/studio 2>&1 | tail -n 3; }"
            else -> "true"
        }
        return "$prelude; $VENV; ($cmd; echo \$? > /tmp/orion_rc) 2>&1 | tee /tmp/orion_reg.log; rc=\$(cat /tmp/orion_rc 2>/dev/null || echo 1); echo INSTALL_EXIT=\$rc"
    }

    fun registryInstall(c: Context, a: JSONObject): String {
        val (entry, cmd) = installCommand(a.optString("id").trim(), a.optString("kind"), a.optString("source").trim(), a.optString("run").trim())
            ?: return "error: unknown registry id; give a catalogue id or {kind, source:\"npm:<pkg>\"|\"pip:<pkg>\", id}."
        val out = OrionProgress.run(c, "Installing " + entry.name, installScript(cmd, ubuntu(c)), OrionUbuntu.where(c)).lines().takeLast(30).joinToString("\n")
        if (!out.contains("INSTALL_EXIT=0")) return "Install did not finish cleanly:\n$out"
        val m = installed(c)
        m[entry.id] = JSONObject().put("id", entry.id).put("kind", entry.kind.name).put("name", entry.name).put("about", entry.about).put("install", entry.install).put("run", entry.run).put("at", System.currentTimeMillis())
        saveAll(c, m)
        return "Installed ${entry.name} (${entry.kind.name.lowercase()})." + if (entry.run.isNotBlank()) " Start it with studio {action:\"registry_run\", id:\"${entry.id}\"}." else ""
    }

    fun registryInstallVsx(c: Context, id: String): String {
        val script = vsxInstallScript(id.trim()) ?: return "error: give an Open VSX id like publisher.extension."
        val out = OrionProgress.run(c, "Installing " + id.trim(), script, OrionUbuntu.where(c)).lines().takeLast(30).joinToString("\n")
        if (!out.contains("VSX_INSTALLED")) return "Install did not finish cleanly:\n$out"
        val m = installed(c)
        m[id.trim()] = JSONObject().put("id", id.trim()).put("kind", "EXTENSION").put("name", id.trim()).put("about", "VS Code extension from Open VSX").put("install", "").put("run", "").put("at", System.currentTimeMillis())
        saveAll(c, m)
        return "Installed VS Code extension ${id.trim()} into /workspace/.vsx (loaded by code-server: start the code-server extension with registry_run)."
    }

    private fun mcpRun(c: Context, id: String): String? = installed(c)[id]?.optString("run")?.takeIf { it.isNotBlank() }
        ?: catalog.firstOrNull { it.id == id && it.kind == Kind.MCP }?.run?.takeIf { it.isNotBlank() && installed(c).containsKey(id) }

    fun mcpTools(c: Context, id: String): String {
        val run = mcpRun(c, id) ?: return "error: $id is not an installed MCP server with a run command. Install it first with registry_install."
        return ShellRunner.run(c, mcpScript(run, "tools/list", JSONObject()) ?: return "error: bad MCP command.", OrionUbuntu.where(c))
    }

    fun mcpCall(c: Context, id: String, tool: String, arguments: JSONObject): String {
        val run = mcpRun(c, id) ?: return "error: $id is not an installed MCP server with a run command."
        if (!Regex("^[A-Za-z0-9_.-]{1,80}$").matches(tool)) return "error: bad tool name."
        val params = JSONObject().put("name", tool).put("arguments", arguments)
        return ShellRunner.run(c, mcpScript(run, "tools/call", params) ?: return "error: bad MCP command.", OrionUbuntu.where(c))
    }

    fun registryRemove(c: Context, id: String): String {
        val m = installed(c)
        if (m.remove(id) == null) return "error: $id is not installed."
        saveAll(c, m)
        return "Removed $id from the registry (packages stay in the runtime until you uninstall them)."
    }

    fun registryRun(c: Context, id: String, port: Int): String {
        val j = installed(c)[id] ?: return "error: install $id first."
        if (!j.optBoolean("enabled", true)) return "error: $id is disabled; enable it first."
        val run = j.optString("run")
        if (run.isBlank()) return "$id has nothing to run; it is a tool used from the shell."
        return OrionDevspace.startService(c, id.take(40), "$VENV; $run", port, "")
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
        fun guestLive(s: String?, bad: String, label: String): String =
            if (s == null) "error: $bad" else OrionProgress.run(c, label, s, where).lines().takeLast(60).joinToString("\n")
        return when (action) {
            "", "help" -> "studio actions: status (everything running: objective, workers, services, jobs, registry), toolchain_list, toolchain_install {name: ${toolchains.keys.joinToString("|")}}, " +
                "lsp_install {lang: python|typescript|go|c|bash|rust}, diagnostics {project}, format {project}, complete {file,line,col}, symbols {file}, highlight {file}, " +
                "debug {file, breakpoints?:[lines], commands?:[pdb cmds]}, git {op: init|status|diff|log|branch|checkout|add|commit|stash|stash_pop|blame|show|remote|pull|push, project, message?, ref?, file?}, git_clone {url,name}, " +
                "build {project,target?}, test {project}, preview {project,port?,command?}, preview_stop {name}, " +
                "registry_list {kind?}, registry_search {query}, registry_npm {query}, registry_install {id | kind+source}, registry_remove {id}, registry_run {id,port?}, registry_enable/registry_disable/registry_update {id}, runtime_status, runtime_select {runtime: auto|ubuntu|linux}, terminal {command, project?}, packages {manager: apt|apk|pip|npm, op: list|search|install|remove|update, names[]}, snapshot {project,label?}, snapshots, restore {name}, rollback {project,ref,confirm}, dev_task {objective, project?} (Orion Dev Agent: autonomous inspect-plan-edit-execute-debug-verify worker), open {page: editor|terminal|runtime|packages|extensions|debug|build|git|agent|recovery}, registry_discover {source: openvsx|npm|mcp, query} (live search of Open VSX, npm and the official MCP registry), registry_install_vsx {id}, mcp_tools {id} and mcp_call {id, tool, arguments} (real MCP client for installed servers), file_diagnostics {file}, hover {file,line,col}. Show code to the owner through cards: write it to /workspace files and mention the path; do not paste long code in chat. Projects live under /workspace."
            "status" -> overview(c)
            "ubuntu_install" -> { OrionProgress.start("Ubuntu 24.04"); OrionUbuntu.install(c).also { OrionProgress.done(!it.startsWith("error:")) } }
            "toolchain_list" -> guest(toolchainListScript(), "")
            "toolchain_install" -> toolchainScript(a.optString("name").lowercase(), ubuntu(c))?.let { OrionProgress.run(c, "Installing " + a.optString("name"), it, where).lines().takeLast(40).joinToString("\n") } ?: "error: unknown toolchain; use ${toolchains.keys.joinToString(", ")}."
            "lsp_install" -> guestLive(lspInstallScript(a.optString("lang").lowercase()), "unknown language; use python, typescript, go, c, bash or rust.", "Language tools · " + a.optString("lang"))
            "diagnostics" -> guest(diagnosticsScript(project), "bad project path.")
            "format" -> { snapshotScript(project, "before-format")?.let { ShellRunner.run(c, it, where) }; guest(formatScript(project), "bad project path.") }
            "complete" -> guest(completeScript(a.optString("file"), a.optInt("line", 1), a.optInt("col", 0)), "complete needs a relative file, line and col.")
            "symbols" -> guest(symbolsScript(a.optString("file")), "bad file path.")
            "highlight" -> guest(highlightScript(a.optString("file")), "bad file path.")
            "debug" -> guest(debugScript(a.optString("file"),
                a.optJSONArray("breakpoints")?.let { b -> List(b.length()) { b.optInt(it) } } ?: emptyList(),
                a.optJSONArray("commands")?.let { b -> List(b.length()) { b.optString(it) } } ?: emptyList()), "debug supports .py, .js, .c/.cpp with valid breakpoints.")
            "git" -> guest(gitScript(a.optString("op").lowercase(), project, a), "bad git op or arguments.")
            "git_clone" -> guest(cloneScript(a.optString("url"), a.optString("name")), "clone needs an https url and a safe name.")
            "build", "test" -> {
                val kind = action
                val out = guest(buildScript(project, kind, if (kind == "build") a.optString("target").trim() else ""), "bad project or target.")
                if (out.startsWith("error:")) out else report(out, kind).summary + "\n\n" + out
            }
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
            "registry_discover" -> discover(a.optString("source"), a.optString("query"))
            "registry_install_vsx" -> registryInstallVsx(c, a.optString("id"))
            "mcp_tools" -> mcpTools(c, a.optString("id"))
            "mcp_call" -> mcpCall(c, a.optString("id"), a.optString("tool"), a.optJSONObject("arguments") ?: JSONObject())
            "file_diagnostics" -> guest(fileDiagnosticsScript(a.optString("file")), "file_diagnostics needs a relative file path with a supported extension.")
            "hover" -> guest(hoverScript(a.optString("file"), a.optInt("line", 1), a.optInt("col", 0)), "hover supports Python files with a valid line/col.")
            "runtime_status" -> runtimeStatus(c)
            "runtime_select" -> if (OrionUbuntu.setChoice(c, a.optString("runtime").lowercase())) runtimeStatus(c) else "error: runtime must be auto, ubuntu or linux."
            "terminal" -> {
                val cmd = a.optString("command")
                val d = dir(project)
                if (cmd.isBlank() || cmd.length > 4000 || d == null) "error: terminal needs a command and a valid project."
                else ShellRunner.run(c, "cd $d 2>/dev/null || cd /workspace; $VENV; $cmd", where)
            }
            "packages" -> guestLive(packageScript(a.optString("manager").lowercase(), a.optString("op").lowercase().ifBlank { "list" },
                a.optJSONArray("names")?.let { n -> List(n.length()) { n.optString(it) } } ?: a.optString("name").split(' ', ',').filter { it.isNotBlank() }, ubuntu(c)),
                "bad package request (managers apt|apk|pip|npm; ops list|search|install|remove|update).", "Packages · " + a.optString("op").ifBlank { "list" })
            "snapshot" -> guest(snapshotScript(project, a.optString("label")), "bad project path.")
            "snapshots" -> guest(snapshotListScript(), "")
            "restore" -> guest(restoreScript(a.optString("name")), "give a snapshot name from studio snapshots.")
            "rollback" -> if (!a.optBoolean("confirm")) "Rollback discards uncommitted work (it is stashed first). Ask the owner, then call again with confirm:true."
                else guest(rollbackScript(project, a.optString("ref").ifBlank { "HEAD" }), "bad ref.")
            "dev_task" -> devTask(c, a.optString("objective"), project)
            "open" -> openPage(c, a.optString("page"))
            "registry_enable" -> registrySetEnabled(c, a.optString("id"), true)
            "registry_disable" -> registrySetEnabled(c, a.optString("id"), false)
            "registry_update" -> registryUpdate(c, a.optString("id"))
            "registry_remove" -> registryRemove(c, a.optString("id"))
            "registry_run" -> registryRun(c, a.optString("id"), a.optInt("port", 0))
            else -> "error: unknown studio action '$action'. Call studio {action:\"help\"}."
        }
    }
}
