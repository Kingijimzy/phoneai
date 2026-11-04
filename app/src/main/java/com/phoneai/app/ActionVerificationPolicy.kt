package com.phoneai.app

/**
 * Decides what counts as evidence that a UI action took effect.
 *
 * A changed screen is strong evidence. No change is ambiguous, not proof of failure:
 * canvas/game/video screens, already-open activities and visually identical states
 * legitimately produce no text diff. Treating "no change" as an error made Orion pause
 * correct tasks; treating it as success would be dishonest. So it stays UNVERIFIED.
 */
internal object ActionVerificationPolicy {
    enum class Outcome { PASS, UNVERIFIED }

    fun outcome(screenChanged: Boolean, packageChanged: Boolean): Outcome =
        if (screenChanged || packageChanged) Outcome.PASS else Outcome.UNVERIFIED

    fun passNote(): String =
        "LOCAL VERIFY PASS: visible state changed after this action. This is evidence of a UI change, not proof of the user's overall goal."

    fun unverifiedNote(tool: String): String =
        "UNVERIFIED: no visible screen change was detected after '$tool'. The action may still have taken effect " +
            "(canvas, video, or an already-open screen), so treat the outcome as unconfirmed: check the specific " +
            "postcondition or ask the user before claiming success."

    fun gateNote(): String =
        "UNVERIFIED: this step's outcome could not be confirmed by screen evidence; do not claim it succeeded."
}
