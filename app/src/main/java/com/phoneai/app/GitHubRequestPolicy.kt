package com.phoneai.app

import java.net.URI

/**
 * Deterministic parsing for public GitHub URLs and explicitly named repositories.
 * URL targets always win over prose heuristics: words such as "code" or "project"
 * must never be mistaken for a repository name when an exact URL is present.
 */
internal object GitHubRequestPolicy {
    enum class Section { REPOSITORY, RUNS, RUN }

    data class Target(
        val owner: String,
        val repository: String,
        val section: Section,
        val runId: String? = null
    )

    private val githubUrl = Regex("(?i)https?://(?:www\\.)?github\\.com/[^\\s<>\\\"]+")
    private val safePart = Regex("^[A-Za-z0-9_.-]{1,100}$")
    private val reservedNames = setOf(
        "code", "source", "files", "file", "entire", "all", "everything", "project",
        "repository", "repo", "github", "account", "check", "inspect", "review",
        "workflow", "workflows", "actions", "build", "builds", "logs", "history"
    )

    fun resolveAll(command: String): List<Target> {
        val fromUrls = githubUrl.findAll(command).mapNotNull { match ->
            parseUrl(match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}'))
        }.distinctBy { listOf(it.owner.lowercase(), it.repository.lowercase(), it.section.name, it.runId.orEmpty()).joinToString(":") }.toList()
        if (fromUrls.isNotEmpty()) return fromUrls

        val explicit = Regex(
            "(?i)\\b(?:my\\s+)?(?:github\\s+)?(?:repo|repository|project)\\s+(?:(?:called|named|is)\\s+)?([A-Za-z0-9_.-]{2,100})\\b"
        ).find(command)?.groupValues?.getOrNull(1)?.trim()
        if (!explicit.isNullOrBlank() && explicit.lowercase() !in reservedNames && safePart.matches(explicit)) {
            return listOf(Target("", explicit, Section.REPOSITORY))
        }
        return emptyList()
    }

    private fun parseUrl(raw: String): Target? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        if (!uri.host.equals("github.com", true) && !uri.host.equals("www.github.com", true)) return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repository = parts[1].removeSuffix(".git")
        if (!safePart.matches(owner) || !safePart.matches(repository) ||
            owner == "." || owner == ".." || repository == "." || repository == "..") return null

        if (parts.size >= 3 && parts[2].equals("actions", true)) {
            val runId = if (parts.size >= 5 && parts[3].equals("runs", true) && parts[4].matches(Regex("[0-9]{1,20}"))) parts[4] else null
            return Target(owner, repository, if (runId == null) Section.RUNS else Section.RUN, runId)
        }
        return Target(owner, repository, Section.REPOSITORY)
    }
}
