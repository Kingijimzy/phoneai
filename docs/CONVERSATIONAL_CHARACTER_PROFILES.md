# Conversational Character Profiles

## What this adds
PhoneAI now separates an avatar's visual design from its conversational character. The user can choose four persistent profiles in the Voice section:

- **Orion — calm strategist:** composed, perceptive, lightly witty when natural.
- **Nova — warm companion:** friendly and emotionally attentive without exaggerated enthusiasm or claims of human feelings.
- **Atlas — direct operator:** concise, decisive, practical, risk-aware.
- **Lumen — patient guide:** reflective, patient, and clear when explaining difficult topics.

The chosen profile is saved in the existing private app preferences and is applied to both ordinary chat and the phone-control agent's system prompt. Existing installations default to Orion. No new permission or API key is required.

## Human-like behavior boundaries
The profile instructions aim for consistent tone, contextual continuity, natural acknowledgement, and useful initiative. They explicitly prohibit fabricated memories, personal experiences, feelings, and capabilities. The character should sound natural without manipulating the user or pretending to be a human.

## Quota/reliability guardrails included
The phone-control agent's maximum reasoning/tool loop is reduced from 40 to 16 turns, and a single batched response may execute at most four tool calls before returning results to the model. Plain chat still uses the existing one-call path and six-message chat context. These are conservative limits to reduce runaway API use; complex tasks may need a follow-up instruction.

## Manual acceptance checklist
1. Open the app's Voice section and choose each personality.
2. Close/reopen the app and confirm the selection persists.
3. Ask the same conversational question in each profile and confirm tone changes while factual content stays grounded.
4. Ask Orion to perform a phone task and confirm the chosen profile also applies to its agent prompt.
5. Test a multi-step task and confirm the agent stops at the bounded loop limit rather than making unlimited calls.
6. Confirm existing avatar selection, provider selection, memory, voice, and reminders still work.

## Verification boundary
A GitHub Actions build and physical-device test are still required before calling this milestone verified on-device.
