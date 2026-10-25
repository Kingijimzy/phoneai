# Stage 5 — Final agent-loop integration

## Purpose

Stage 5 connects the safeguards added in Stages 1–4 at the real action-dispatch boundary. It is a final integration and regression-hardening pass, not a claim that the whole product has been verified on a physical phone.

## Implementation in this stage

- Route each proposed tool action through one deterministic dispatch policy combining task-mode scope, the user's request scope, sensitive-screen/credential checks, and cancellation state.
- Keep consequential-action approval separate from the policy gate. The model cannot approve its own action, and passing the gate does not replace the app's explicit user confirmation.
- Re-check cancellation after the model returns, before a late model plan can be processed.
- Re-check cancellation immediately before executing a side effect, including after an approval prompt, so Stop prevents queued actions from starting afterward.
- Preserve the existing provider budget, narrow HTTP 429 fallback, deterministic Android route allowlist, bounded skills, checkpoint recovery, action verification, and privacy-limited action log rather than creating duplicate systems.

## Automated regression coverage

\`AgentDispatchPolicyTest\` checks:

- Stop denies an otherwise valid action.
- Unknown tools, ordinary chat tool calls, and write actions from research/read-only tasks are denied.
- Explicitly requested sending is not confused with draft-only or negated sending.
- Credential entry is blocked while ordinary game/chat vocabulary such as “wallet”, “card”, “transfer”, and “pin this message” remains usable on an ordinary screen.
- Read-only inspection can proceed while sensitive banking/payment-app actions remain blocked.

## Verification boundary

A green GitHub Actions run verifies that JVM tests pass and the debug APK compiles and passes the workflow's package/signature checks. It does **not** prove that microphone, wake word, accessibility, keyboard, UI animations, task resume, or every screen works on a Redmi device. Physical-device acceptance items remain explicitly pending until tested on the device; no device success is inferred from compilation alone.
