package com.phoneai.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Commands Orion implements itself, so its terminal works with no Termux and no Linux userland:
 * curl/wget/fetch (https GET), unzip, json (query), js (sandboxed JavaScript) and help.
 * Parsing and triage are pure and unit tested; execution uses only Android and the JVM.
 */
internal object BuiltinCommands {
    val names = setOf("curl", "wget", "fetch", "unzip", "json", "js", "help")
    private const val MAX_DOWNLOAD = 2_000_000
    private const val MAX_UNZIP_TOTAL = 20_000_000L
    private const val MAX_UNZIP_ENTRIES = 500

    /**
     * Shell-like split honouring single and double quotes. Returns null on an unterminated quote or when a shell
     * operator (| ; & < > backtick, newline) appears outside quotes, so compound lines still go to the Android shell.
     */
    fun tokenize(line: String): List<String>? {
        val out = ArrayList<String>(); val cur = StringBuilder(); var quote = 0.toChar(); var any = false
        for (ch in line) {
            when {
                quote != 0.toChar() -> if (ch == quote) quote = 0.toChar() else cur.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; any = true }
                ch in "|;&<>`\n\r" -> return null
                ch.isWhitespace() -> if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(ch)
            }
        }
        if (quote != 0.toChar()) return null
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    fun parse(line: String): List<String>? {
        val t = tokenize(line.trim()) ?: return null
        if (t.isEmpty() || t[0] !in names) return null
        if (t.drop(1).any { it.isEmpty() && t[0] != "js" }) return null
        return t
    }

    private fun relative(p: String) = p.isNotEmpty() && !p.startsWith("/") && !p.startsWith("~") && !p.contains("..") && !p.contains("$")

    /** SAFE/ASK for a built-in line, or null when the line is not a built-in. */
    fun risk(line: String): ShellRisk? {
        val t = parse(line) ?: return null
        val args = t.drop(1)
        return when (t[0]) {
            "help" -> ShellRisk.SAFE
            // Network access can leak data in a URL, so downloads always need approval (or Trusted mode); never SAFE.
            "curl", "wget", "fetch" -> ShellRisk.ASK
            "unzip" -> { val paths = args.filter { !it.startsWith("-") }; if (paths.isNotEmpty() && paths.all { relative(it) }) ShellRisk.SAFE else ShellRisk.ASK }
            "json" -> if (args.size in 1..2 && relative(args[0])) ShellRisk.SAFE else ShellRisk.ASK
            "js" -> if (args.size == 1 && (args[0].endsWith(".js") && relative(args[0]) || !args[0].endsWith(".js"))) ShellRisk.SAFE else ShellRisk.ASK
            else -> null
        }
    }

    /** Dotted/indexed path like a.b[0].c over parsed JSON. */
    fun jsonPath(root: Any, path: String): Any? {
        var cur: Any? = root
        if (path.isBlank() || path == ".") return cur
        for (part in Regex("[^.\\[\\]]+|\\[\\d+]").findAll(path).map { it.value }) {
            cur = when {
                part.startsWith("[") -> (cur as? JSONArray)?.opt(part.trim('[', ']').toInt())
                cur is JSONObject -> cur.opt(part)
                cur is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    fun helpText() = "Orion built-in terminal (no Termux needed):\n" +
        "  curl|wget|fetch [-o file] https://url   download or print a page (https GET, 2 MB max)\n" +
        "  unzip file.zip [-d dir]                 extract inside the workspace\n" +
        "  json file.json path.to[0].value         read a value from JSON\n" +
        "  js 'code' | js file.js                  run JavaScript in a sandbox (no network); use console.log\n" +
        "  plus the Android shell: ls cat grep sed awk sort head tail wc cp mkdir ... (workspace only)"

    // ---------- execution (Android) ----------

    fun run(c: Context, line: String): String? {
        val t = parse(line) ?: return null
        val args = t.drop(1)
        return try {
            when (t[0]) {
                "help" -> helpText()
                "curl", "wget", "fetch" -> download(c, args)
                "unzip" -> unzip(c, args)
                "json" -> jsonCmd(c, args)
                "js" -> jsCmd(c, args)
                else -> null
            }
        } catch (e: Exception) { "error: ${t[0]} failed (" + (e.message ?: e.javaClass.simpleName).take(120) + ")" }
    }

    private fun download(c: Context, args: List<String>): String {
        var out: String? = null; var url: String? = null; var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-o" && i + 1 < args.size -> { out = args[++i] }
                a == "-O" -> out = "" // name taken from the URL
                a.startsWith("-") -> if (a !in setOf("-s", "-S", "-L", "-f", "-q", "-sL", "-sS", "-sSL", "-fsSL", "-fsS")) return "error: unsupported option $a (built-in fetch supports -o FILE, -O, -L, -s)"
                else -> url = a
            }
            i++
        }
        val u = url ?: return "error: usage: curl [-o file] https://url"
        if (!u.startsWith("https://")) return "error: only https URLs are allowed"
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Orion-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return "error: HTTP $code"
            val bytes = conn.inputStream.use { s ->
                val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val n = s.read(chunk); if (n < 0) break; buf.write(chunk, 0, n); if (buf.size() > MAX_DOWNLOAD) return "error: response larger than 2 MB; not downloaded" }
                buf.toByteArray()
            }
            if (out != null) {
                val name = if (out.isEmpty()) URL(u).path.substringAfterLast('/').ifBlank { "download.html" } else out
                val f = ShellRunner.safePath(c, name) ?: return "error: output must be a relative path inside the workspace"
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
                return "saved ${bytes.size} bytes to workspace/$name"
            }
            val text = String(bytes, Charsets.UTF_8)
            return if (text.count { it == '\u0000' } > 0) "(binary, ${bytes.size} bytes; use -o file)" else ShellPolicy.clip(text)
        } finally { conn.disconnect() }
    }

    private fun unzip(c: Context, args: List<String>): String {
        var dir = ""; var zip: String? = null; var i = 0
        while (i < args.size) { val a = args[i]; if (a == "-d" && i + 1 < args.size) dir = args[++i] else if (!a.startsWith("-")) zip = a; i++ }
        val zf = ShellRunner.safePath(c, zip ?: return "error: usage: unzip file.zip [-d dir]") ?: return "error: path must be inside the workspace"
        if (!zf.isFile) return "error: no such file"
        val root = if (dir.isBlank()) ShellRunner.workspace(c) else (ShellRunner.safePath(c, dir) ?: return "error: -d must be inside the workspace")
        root.mkdirs()
        val base = root.canonicalFile
        var total = 0L; var count = 0
        ZipInputStream(zf.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (++count > MAX_UNZIP_ENTRIES) return "error: more than $MAX_UNZIP_ENTRIES entries; stopped"
                val target = File(base, e.name).canonicalFile
                if (!target.path.startsWith(base.path + File.separator)) return "error: unsafe path in archive (${e.name.take(60)}); stopped"
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { o ->
                    val buf = ByteArray(8192)
                    while (true) { val n = zis.read(buf); if (n < 0) break; total += n; if (total > MAX_UNZIP_TOTAL) return "error: archive expands beyond 20 MB; stopped"; o.write(buf, 0, n) }
                }
            }
        }
        return "extracted $count entries ($total bytes)"
    }

    private fun jsonCmd(c: Context, args: List<String>): String {
        val f = ShellRunner.safePath(c, args.getOrNull(0) ?: return "error: usage: json file.json [path]") ?: return "error: path must be inside the workspace"
        if (!f.isFile) return "error: no such file"
        val root = JSONTokener(f.readText()).nextValue() ?: return "error: empty JSON"
        val v = jsonPath(root, args.getOrElse(1) { "" }) ?: return "error: path not found"
        return ShellPolicy.clip(if (v is JSONObject) v.toString(2) else if (v is JSONArray) v.toString(2) else v.toString())
    }

    private fun jsCmd(c: Context, args: List<String>): String {
        val src = args.getOrNull(0) ?: return "error: usage: js 'code' or js file.js"
        val code = if (src.endsWith(".js")) {
            val f = ShellRunner.safePath(c, src) ?: return "error: path must be inside the workspace"
            if (!f.isFile) return "error: no such file"; f.readText().take(100_000)
        } else src
        return JsSandbox.eval(c, code)
    }
}

/** JavaScript in an offscreen WebView with network loads blocked: a built-in scripting runtime with no download. */
internal object JsSandbox {
    fun wrap(code: String): String =
        "(function(){var __o=[];var console={log:function(){__o.push(Array.prototype.slice.call(arguments).map(function(x){return typeof x==='string'?x:JSON.stringify(x)}).join(' '))}};" +
            "try{var __r=eval(" + JSONObject.quote(code) + ");if(__r!==undefined)__o.push(typeof __r==='string'?__r:JSON.stringify(__r));}catch(e){__o.push('error: '+e.message)}return __o.join('\\n')})()"

    fun eval(c: Context, code: String, timeoutMs: Long = 6000): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "error: js must run off the main thread"
        val latch = CountDownLatch(1); val result = AtomicReference("error: js did not finish (timed out and was stopped)")
        val holder = AtomicReference<WebView?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(c.applicationContext); holder.set(wv)
                wv.settings.javaScriptEnabled = true
                wv.settings.blockNetworkLoads = true
                wv.settings.allowFileAccess = false; wv.settings.allowContentAccess = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(wrap(code)) { r ->
                            result.set(try { (JSONTokener(r ?: "null").nextValue() as? String).orEmpty().ifBlank { "(no output)" } } catch (_: Exception) { r ?: "" })
                            latch.countDown()
                        }
                    }
                }
                wv.loadDataWithBaseURL("https://orion.invalid/", "<html><body></body></html>", "text/html", "utf-8", null)
            } catch (e: Exception) { result.set("error: JavaScript engine unavailable (" + (e.message ?: "").take(80) + ")"); latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        Handler(Looper.getMainLooper()).post { try { holder.get()?.destroy() } catch (_: Exception) { } }
        return ShellPolicy.clip(result.get())
    }
}
