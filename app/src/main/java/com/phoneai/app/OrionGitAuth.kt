package com.phoneai.app

import android.util.Base64

/**
 * Lets `git` in Orion's shell use the owner's connected GitHub token without ever showing it to the model.
 * The token goes in through git's own config environment for that one command, only for plain git
 * network operations, and is scrubbed from whatever the command prints.
 */
internal object OrionGitAuth {
    private val gitNetwork = Regex("""(^|[\s;&|(])git\s+(?:-C\s+\S+\s+)?(push|pull|fetch|clone|ls-remote)\b""")
    // Anything that could read the environment or send it elsewhere keeps the token out.
    private val leaky = Regex("""(?i)\b(curl|wget|nc|ncat|socat|telnet|ssh|scp|printenv|env|export|set|declare|base64|openssl|python3?|node|perl|ruby|php|awk|tee|xargs|eval|source)\b|\$\{?GIT_|\$\{?GH_|\$\{?GITHUB|GIT_TRACE|GIT_CURL|--trace|-c\s+core\.(askpass|sshcommand)|credential\.helper|[`]|\$\(|>|<""")

    internal fun header(token: String): String =
        "AUTHORIZATION: basic " + encode("x-access-token:$token")

    private fun encode(s: String): String =
        try { Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP) }
        catch (_: Throwable) { java.util.Base64.getEncoder().encodeToString(s.toByteArray()) }

    fun applies(command: String): Boolean = gitNetwork.containsMatchIn(command) && !leaky.containsMatchIn(command)

    fun wrap(token: String, command: String): String =
        if (token.isBlank() || !applies(command)) command
        else "GIT_TERMINAL_PROMPT=0 GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=http.https://github.com/.extraheader " +
            "GIT_CONFIG_VALUE_0='${header(token)}' sh -c ${shellQuote(command)}"

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun scrub(token: String, output: String): String =
        if (token.isBlank()) output
        else output.replace(token, "***").replace(header(token), "***").replace(encode("x-access-token:$token"), "***")

    fun token(c: android.content.Context): String =
        runCatching { OrionConnectionStore.tokenForConnector(c, "github") }.getOrDefault("")
}
