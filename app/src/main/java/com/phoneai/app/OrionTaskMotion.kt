package com.phoneai.app

/** Which motion a running task gets, so each kind of work looks different, like Ultron's per-task animations. */
internal object OrionTaskMotion {
    enum class Kind { CODE, WEB, DEVICE, MEMORY, LINK, MUSIC, THINK }

    fun kindFor(tool: String): Kind = when {
        tool in setOf("python_exec", "dev_python", "dev_shell", "shell", "devspace", "termux_command", "workspace_write", "dev_write",
            "extension_forge", "extension_run") || tool.startsWith("ext_") -> Kind.CODE
        tool.startsWith("browser") || tool in setOf("live_web_search", "web_search", "research_web", "fetch_url", "web_api", "open_url") -> Kind.WEB
        tool in setOf("mesh", "a2a") -> Kind.LINK
        tool in setOf("recall", "remember", "fact_add", "fact_query", "brain") -> Kind.MEMORY
        tool in setOf("play_media", "media_control") -> Kind.MUSIC
        tool in setOf("open_app", "click_text", "type", "read_screen", "play", "volume", "flashlight", "settings") -> Kind.DEVICE
        else -> Kind.THINK
    }

    /** One short word per kind, shown beside the glyph so the motion is also readable without animation. */
    fun label(kind: Kind) = when (kind) {
        Kind.CODE -> "building"; Kind.WEB -> "reaching out"; Kind.DEVICE -> "on your phone"
        Kind.MEMORY -> "remembering"; Kind.LINK -> "linking"; Kind.MUSIC -> "playing"; Kind.THINK -> "thinking"
    }

    /** Work-status text from the agent (no tool name) mapped onto a motion. */
    fun kindForPhase(phase: String): Kind = phase.lowercase().let { v ->
        when {
            listOf("search", "research", "browser", "look up", "find", "web").any { it in v } -> Kind.WEB
            listOf("code", "build", "develop", "implement", "write", "script", "terminal", "command", "execut", "forge").any { it in v } -> Kind.CODE
            listOf("memory", "recall", "remember").any { it in v } -> Kind.MEMORY
            listOf("mesh", "peer", "agent", "a2a").any { it in v } -> Kind.LINK
            listOf("music", "play").any { it in v } -> Kind.MUSIC
            listOf("screen", "tap", "open app", "phone").any { it in v } -> Kind.DEVICE
            else -> Kind.THINK
        }
    }
}
