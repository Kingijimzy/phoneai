package com.phoneai.app

/**
 * Mood and intent scenes, Orion's version of Ultron's "I feel like coding" workstation setup.
 * A scene is detected from what the owner said, brings its tools into reach for the turn, and
 * gives Orion a concrete opening move, so he sets the situation up instead of asking what to do.
 */
internal object OrionScenes {
    data class Scene(val id: String, val trigger: Regex, val tools: List<String>, val brief: String)

    private val scenes = listOf(
        Scene("coding",
            Regex("""(?i)\b(feel(ing)? like|want to|wanna|let'?s|lets|time to|gonna|going to|mood to)\s+(do some\s+)?(cod(e|ing)|build(ing)?|ship(ping)?|program(ming)?)\b|\bcoding (mode|time|session)\b"""),
            listOf("devspace", "workspace_read", "shell", "github_account_read", "python_exec", "extension_forge"),
            "SCENE coding: he wants to build. Open the code space with devspace, look at the active project and the repo's open PRs and CI, then give two lines on what is waiting and start on the most important item. Do not ask what to work on."),
        Scene("trading",
            Regex("""(?i)\b(trading (mode|time|session)|check the (market|markets|charts)|let'?s trade|time to trade|market (brief|check|open))\b"""),
            listOf("live_web_search", "recall", "calendar_events", "task_list"),
            "SCENE trading: he is about to trade. Pull the current BTC and ETH prices and today's market-moving events, recall his trading rules and open plans, and give a three-line brief: what moved, what is scheduled, what his own rules say. No advice to buy or sell."),
        Scene("focus",
            Regex("""(?i)\b(focus (mode|time)|need to focus|deep work|don'?t disturb me|no distractions|help me concentrate)\b"""),
            listOf("quiet_period_set", "task_list", "calendar_events"),
            "SCENE focus: protect his attention. Set a quiet period, list his top three tasks, and say in one line what you will hold back until it ends."),
        Scene("morning",
            Regex("""(?i)\b(good morning|morning brief|start my day|what'?s (on )?today)\b"""),
            listOf("calendar_events", "task_list", "live_web_search", "recall"),
            "SCENE morning: give him his day in four lines: schedule, tasks due, anything that broke overnight, one thing worth his attention. Lead with the most important."),
        Scene("gaming",
            Regex("""(?i)\b(game (time|mode)|gaming (mode|time)|let'?s (play|game)|wanna play|feel like (playing|gaming)|play (fc mobile|fifa|ea fc))\b"""),
            listOf("open_app", "quiet_period_set", "read_screen"),
            "SCENE gaming: he wants to play. Open the game he names (FC Mobile if he does not say), set a quiet period so nothing interrupts, and say only what you are holding back. Do not start a conversation."),
        Scene("tired",
            Regex("""(?i)\b(i'?m (so )?(tired|exhausted|drained|stressed|frustrated|fed up)|i give up|this is (too much|frustrating)|i'?m done)\b"""),
            emptyList(),
            "SCENE tired: he is worn down. Keep it short and warm, no status dump. Say one honest line about what is actually fixable, take one concrete step yourself, and offer rest or the single easiest next thing.")
    )

    /** Owner edits, pushed in by OrionMindStore.sync. */
    @Volatile var disabled: Set<String> = emptySet()
    @Volatile var briefOverrides: Map<String, String> = emptyMap()

    fun all(): List<Scene> = scenes

    fun detect(request: String?): Scene? =
        request?.takeIf { it.isNotBlank() }?.let { r -> scenes.firstOrNull { it.id !in disabled && it.trigger.containsMatchIn(r) } }

    fun toolsFor(request: String?): List<String> = detect(request)?.tools.orEmpty()

    fun brief(request: String?): String = detect(request)?.let { briefOverrides[it.id] ?: it.brief }.orEmpty()
}
