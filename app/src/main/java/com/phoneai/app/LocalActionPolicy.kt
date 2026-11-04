package com.phoneai.app

/**
 * Classifies the deterministic "instant command" paths in [Local].
 *
 * Local answers common commands without the model. Most are harmless local answers
 * (time, maths, saved notes), but the device-action branches used to run even when the
 * phone_control pack was switched off, so the pack toggle did not actually govern them
 * and the fast path skipped the gated tool pipeline. The agent now skips Local for these
 * commands while the pack is off; the normal gated path explains that it is disabled.
 */
internal object LocalActionPolicy {
    private val deviceAction = Regex(
        "(?i)\\b(" +
            "flash ?light|torch|volume|mute|unmute|camera|photo|picture|selfie|" +
            "navigate|directions|drive|route|whatsapp|youtube|dial|call|" +
            "settings|recording|screen record|record|alarm|timer|" +
            "screenshot|lock|home screen|go home|recent apps|recents|" +
            "notifications|quick settings|power menu|scroll|" +
            "click|tap|press|select|hit|open|launch|go to|" +
            "google|search|look up|read (my|the|this)? ?screen" +
            ")\\b"
    )

    /** True when [command] is handled by a Local branch that performs a phone_control action. */
    fun requiresPhoneControl(command: String): Boolean = deviceAction.containsMatchIn(command)
}
