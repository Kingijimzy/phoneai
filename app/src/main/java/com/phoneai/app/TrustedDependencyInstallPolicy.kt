package com.phoneai.app

/**
 * Allows unattended dependency installation only from Orion's pinned official Alpine 3.22
 * repositories. apk verifies repository signatures; arbitrary repositories, untrusted packages,
 * upgrades/removals, shell expansion and apk override flags remain approval-gated.
 */
internal object TrustedDependencyInstallPolicy {
    private const val MAIN = "https://dl-cdn.alpinelinux.org/alpine/v3.22/main"
    private const val COMMUNITY = "https://dl-cdn.alpinelinux.org/alpine/v3.22/community"
    private val packageToken = Regex("^[A-Za-z0-9][A-Za-z0-9+_.-]{0,63}(?:=[A-Za-z0-9+_.-]{1,64})?$")

    fun isSafeAlpineInstall(command: String): Boolean {
        if (command.length !in 1..1200 || command.any { it == ';' || it == '|' || it == '&' || it == '$' || it == '`' || it == '\n' || it == '\r' }) {
            return false
        }
        val words = command.trim().split(Regex("\\s+"))
        if (words.size < 6 || words[0] != "apk" || words[1] != "add") return false

        var index = 2
        var noCache = false
        val repositories = mutableSetOf<String>()
        val packages = mutableListOf<String>()
        while (index < words.size) {
            when (val token = words[index]) {
                "--no-cache" -> {
                    if (noCache) return false
                    noCache = true
                    index++
                }
                "--repository" -> {
                    val repository = words.getOrNull(index + 1) ?: return false
                    if (repository !in setOf(MAIN, COMMUNITY)) return false
                    repositories += repository
                    index += 2
                }
                else -> {
                    if (token.startsWith("-") || !packageToken.matches(token)) return false
                    packages += token
                    index++
                }
            }
        }
        return noCache && MAIN in repositories && COMMUNITY in repositories && packages.isNotEmpty()
    }
}
