# Conversational Character Profiles

## What this adds
PhoneAI now separates an avatar's visual design from its conversational character. The user can choose four persistent profiles in the Voice section:

- **Orion — calm strategist:** composed, perceptive, lightly witty when natural.
- **Nova — warm companion:** friendly and emotionally attentive without exaggerated enthusiasm or claims of human feelings.
- **Atlas — direct operator:** concise, decisive, practical, risk-aware.
- **Lumen — patient guide:** reflective, patient, and clear when explaining difficult topics.

The chosen profile is saved in the existing private app preferences and is applied to both ordinary chat and the phone-control agent's system prompt. Existing installations default to Orion. No new permission or API key is required. The avatar look is selected separately, so users can combine any visual look with any conversational profile.

## Human-like behavior boundaries
The profile instructions aim for consistent tone, contextual continuity, natural acknowledgement, and useful initiative. They explicitly prohibit fabricated memories, personal experiences, feelings, and capabilities. The character should sound natural without manipulating the user or pretending to be a human.

## Quota/reliability guardrails included
The phone-control agent's maximum reasoning/tool loop is reduced from 40 to 16 turns, and a single batched response may execute at most four tool calls before returning results to the model. Plain chat still uses the existing one-call path and six-message chat context. These are conservative limits to reduce runaway API use; complex tasks may need a follow-up instruction.

## Browser research and declarative skills
The agent has a `research_web`/`fetch_url` read-only research tool that accepts public HTTP(S) pages on standard ports, validates redirect destinations, applies connection/read timeouts and response limits, and returns the source URL with extracted text. Webpage text is explicitly untrusted and must not override system instructions. It does not click links, submit forms, log into websites, or perform purchases.

The Skills section lets the user register a local declarative skill with an ID, purpose, and allowlist of existing tools. New skills start disabled and cannot include executable code. Enable only reviewed definitions. Run an enabled skill with `run skill <id>: <task>`. The agent checks the allowlist, call budget, time budget, and asks before consequential supported actions. Skills are constrained workflows, not downloaded plug-ins or arbitrary code execution.

## Manual acceptance checklist
1. Open the app's Voice section and choose each personality.
2. Choose each of the four avatar looks, then combine it with a different conversational personality.
3. Close/reopen the app and confirm both selections persist.
4. Ask the same conversational question in each profile and confirm tone changes while factual content stays grounded.
5. Ask Orion to perform a phone task and confirm the chosen profile also applies to its agent prompt.
6. Register a skill with a narrow tool allowlist, verify it starts disabled, enable it, run it explicitly, and confirm disallowed tools and over-budget calls are blocked.
7. Test research_web against a public webpage and verify the source URL appears; try a private/local URL and verify it is rejected.
8. Test a multi-step task and confirm the agent stops at the bounded loop limit rather than making unlimited calls.
9. Confirm existing provider selection, memory, voice, routines, and reminders still work.

## Verification boundary
A GitHub Actions build and physical-device test are still required before calling this milestone verified on-device. Skill registration and runtime authorization are implemented in this branch but have not yet been build- or device-tested.
