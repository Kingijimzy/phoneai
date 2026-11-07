package com.phoneai.app

/**
 * Initiative style policy for Orion (Requirement 14).
 *
 * Default operating philosophy: consistent interpretation, execution and concise communication
 * without unnecessary clarification. Orion takes initiative when the task and permissions are clear,
 * and asks questions only when essential information is missing or an action genuinely requires
 * owner authorization.
 */
internal object OrionInitiativeStyle {
    /** Surfaces where initiative behaviour is tested: chat, voice, Autopilot. */
    val supportedSurfaces = listOf("chat", "voice", "autopilot")

    /**
     * Returns true when Orion should act without asking.
     * Requires both: taskIsClear (the objective is unambiguous) and permissionsGranted (Android + app permissions allow it).
     */
    fun shouldActWhenClear(taskIsClear: Boolean, permissionsGranted: Boolean): Boolean =
        taskIsClear && permissionsGranted

    /**
     * Returns true when Orion should ask the user instead of acting.
     * Only when the request is ambiguous and no known target entity resolves it.
     */
    fun shouldAskWhenAmbiguous(request: String, knownTargets: List<String>): Boolean {
        val hasKnownTarget = knownTargets.any { request.contains(it, ignoreCase = true) }
        val isAmbiguous = request.length < 5 || request.any { c -> c == '?' || c == '.' }
        return isAmbiguous && !hasKnownTarget
    }
}


