# Stage 4 — Autonomous Central-Brain Orchestration: Living Companion Specification
**Date:** 2026-11-01  
**Repository:** `Kingijimzy/phoneai`  
**Implementation branch:** `orion/autonomous-companion-roadmap-20261031`  
**Parent roadmap:** `docs/ORION_AUTONOMOUS_COMPANION_ROADMAP_2026-10-31.md`  
**Status:** Stage 4 extension specification; implementation and device acceptance remain evidence-gated.

## Purpose

Stage 4 must deliver a coherent central intelligence that can perceive authorized context, retrieve relevant knowledge before acting, plan and delegate through the canonical task lifecycle, observe outcomes, correct recoverable failures, improve its skills under owner control, and communicate with a stable recognizable personality.

“Living Jarvis” describes continuity and useful agency—not unrestricted surveillance, fabricated sentience, uncontrolled self-modification, or permission to act without policy checks.

The canonical loop is:

**Perceive → classify context → retrieve → reason → plan → authorize → delegate/act → verify → remember → adapt → report.**

Every entry point (typed chat, Talk, Command, voice, shortcuts, specialists, MCP and background workers) must converge on the existing central brain and canonical objective/task lifecycle. Do not create parallel coordinators, duplicate memory stores, or a second tool/action runtime.

## Existing foundations observed in the implementation branch

The current branch already contains substantial related foundations. These are not equivalent to complete end-to-end acceptance:

- `OrionCentralBrain`: shared executive context, current capability inventory and contextual inputs.
- `OrionSelfDiagnostics` and `OrionSelfHealingPolicy`: provider-free local diagnostics and deterministic repair/extension lifecycle rules.
- `OrionSensorContextSampler`, `OrionSensorContextStore`, `OrionAppAwareness`, notification/device/location/usage context providers: scoped environmental context foundations.
- `MemoryStore`, `MemoryGovernancePolicy`, `MemoryRanking`, `MemoryAutoIndexer`, `OrionKnowledgeGraph`, SQLite FTS and `ContextWindowBudget`: persistent, ranked and provenance-aware memory foundations.
- `CompanionLife` and the existing companion/mood surfaces: greeting and reaction foundations.
- `SkillRegistry`, `SelfExtendingExtensionStore` and `SelfExtendingExtensionPolicy`: declarative skill/extension and integrity foundations.
- Durable task/checkpoint, specialist worker and originating-chat result delivery foundations are documented in the parent roadmap.

The implementation ledger and exact-head CI/device evidence remain authoritative. A class, registry entry, prompt instruction or passing unit test alone does not prove the complete user journey works.

---

## Workstream A — Self-Correction, Self-Healing and Skill Synthesis

### A1. Observe failures as structured evidence

Capture failures at the actual boundaries, not from model speculation:

- Tool dispatch: selected capability, policy decision, bounded error class and outcome evidence.
- Provider transport: provider/model, timeout/rate-limit/empty-response class, retry count and request correlation; never store API keys or sensitive payloads.
- Specialist worker: job ID, parent checkpoint, stage, cancellation/timeout and terminal receipt.
- Android integration: permission/service availability and OS-reported state, without pretending a grant exists.
- Verification: expected postcondition, observed postcondition and whether the result is proven, failed or uncertain.

Deduplicate repeated findings and link every finding to a task, component and evidence reference. Separate transient outages, configuration problems, permission blockers, code defects and uncertain side effects.

### A2. Diagnose before repairing

The central brain should:
1. Retrieve the relevant component contract, recent diagnostics and prior repair outcomes.
2. Reproduce or isolate the failure with a bounded read-only probe where possible.
3. Identify likely cause(s), confidence and missing evidence.
4. Offer a repair proposal with exact files/components, expected effect, risk class, test plan and rollback.
5. Avoid claiming a repair has happened until the change is applied and verified.

### A3. Safe repair lifecycle

Use the existing deterministic repair policy as the authority:

`DETECTED → INVESTIGATING → PROPOSED → TESTING → TEST_PASSED / TEST_FAILED → AWAITING_OWNER (when required) → APPLIED → VERIFIED`

Failure after application must transition to `ROLLED_BACK`, not success.

- Automatic repair is limited to app-owned, explicitly allowlisted operations with a tested rollback path. Inside a verified isolated sandbox, ordinary reversible and data-affecting experiments may run autonomously when idempotent; the sandbox must not expose real device/account state, secrets, permissions, or external side effects.
- Security-sensitive, suspicious, destructive, external-side-effect, permission/policy, and real-device consequential changes require owner approval. A sandbox label supplied by model output is never proof of isolation; only the trusted app-owned runner may select the execution boundary.
- Never auto-change Android permissions, safety gates, trading rules, secrets, provider credentials or capability grants.
- Never blindly retry an uncertain write.
- Record before/after hashes or state snapshots where appropriate, test receipt, approver and rollback receipt.

### A4. Skill synthesis factory

A missing capability should produce a *skill proposal*, not a false claim that a new skill was installed.

`GAP → SOURCE RESEARCH → PROVENANCE/LICENSE REVIEW → DESIGN → DECLARATIVE DRAFT → STATIC VALIDATION → SANDBOX TEST → CAPABILITY/PERMISSION REVIEW → OWNER APPROVAL → VERSIONED ACTIVATION → MONITOR → ROLLBACK`

A skill manifest must include:
- Stable ID, name, purpose, version and compatibility range.
- Source URLs, author/provenance, license identity and evidence.
- Input/output schema and allowed capability/tool IDs.
- Required Android permissions, account setup and owner grants.
- Data classification, memory scope and retention.
- Budget, timeout, retry and cancellation policy.
- Test cases, sandbox receipt, integrity hash and rollback version.

Generated skills remain declarative and capability-scoped. They cannot write privileged policy, alter ToolGate/AgentPolicy, obtain secrets, grant permissions, or execute arbitrary code outside the restricted extension sandbox. Activation is separately approved and revocable.

### A5. Learning from repair outcomes

Only verified outcomes become lessons. Store symptom, cause, evidence, successful remediation, affected versions, confidence, recurrence and expiry. A failed repair is also a lesson, but not a reusable fix. Re-evaluate lessons when the component version or environment changes.

**Acceptance tests**
- A provider timeout is classified as transport failure, not misdiagnosed as a broken skill.
- A failed phone action is checked against actual permission/service state before proposing code changes.
- An uncertain external write is held for reconciliation, never replayed automatically.
- A generated skill with missing license evidence or failing sandbox tests cannot activate.
- A successful low-risk repair is followed by a real verification test and durable receipt.
- A failed verification triggers rollback and reports the actual resulting state.

---

## Workstream B — Ambient Environmental Awareness

### B1. Permissioned context model

Ambient awareness is an owner-controlled context service, not continuous unrestricted recording. Each source has its own grant, purpose, collection mode, freshness, retention and revocation path.

Potential sources:
- Device state: battery, charging, connectivity, thermal state and storage pressure.
- App context: foreground app/package and coarse foreground duration, excluding sensitive apps.
- Notifications: safe, minimized summaries only when Android notification access and Orion's separate consent are both active.
- Motion: coarse accelerometer/gyroscope-derived hints; no raw sensor stream in model prompts.
- Location: approximate/coarsened, short-lived and only when explicitly granted.
- Calendar, routines and time: only connected/authorized sources.
- Screen/accessibility: only during an explicit user action or separately approved session; never silently capture passwords, payment screens or private content.
- Microphone/camera: explicit active session with visible state; no hidden background recording. Raw audio/video is not retained by default.
- Network/research signals: approved feeds and user-defined topics, with source and timestamp attached.

### B2. Context event pipeline

`SOURCE → GRANT CHECK → MINIMIZE → NORMALIZE → REDACT → FRESHNESS CHECK → DEDUPLICATE → PRIORITIZE → CENTRAL BRAIN`

Every context item carries source, timestamp, confidence, expiry, sensitivity and grant scope. The brain must be able to distinguish “not observed”, “permission unavailable”, “stale”, “revoked” and “observed false”. Missing context must never be invented.

### B3. Proactive behaviour

The brain may suggest an action when a meaningful authorized event occurs, but should not interrupt constantly.

- Use relevance thresholds, deduplication, quiet hours and per-source rate limits.
- Explain why an event matters and cite the observed source/time.
- Offer a useful next step; do not execute consequential actions without their required approval.
- Provide pause-all, per-source pause/revoke, retention controls and an emergency stop.
- Respect Android 15/16 background restrictions and HyperOS battery management. Report actual scheduler/service state; do not promise unrestricted always-on monitoring.

### B4. Privacy and resource boundaries

- Default to off for sensitive sources until explicitly enabled.
- Collect the least detail needed; prefer derived summaries over raw streams.
- Keep short-lived context separate from long-term memory.
- Never infer health, identity, emotion, private relationships or other sensitive traits from ambient signals.
- Never use notification, location, screen or app-use context to make financial trades.
- Keep Redmi A3 Pro sampling low-frequency, bounded and battery-aware; remote processing is not permission to upload raw private data.

**Acceptance tests**
- Revoking a source immediately blocks future ingestion and removes/invalidates cached summaries according to retention policy.
- Stale location/motion/device context is excluded from action decisions.
- Notification summaries require both Android access and Orion's grant.
- Background scheduling reports OS-deferred/stopped states honestly.
- Quiet hours and pause-all suppress proactive delivery.
- No raw audio/video or sensitive screen contents enter memory or prompts without a separately explicit, visible workflow.

---

## Workstream C — Tiered Memory and Retrieve-Before-Act

### C1. Canonical memory tiers

Use one canonical memory governance and retrieval layer over existing stores; do not introduce competing stores.

| Tier | Contents | Default lifetime / use |
|---|---|---|
| M0 — Working context | Current turn, active plan, short-lived observations | Session/task scoped; aggressively bounded |
| M1 — Episodic | Conversation events, task receipts, verified outcomes | Searchable history with retention controls |
| M2 — Semantic knowledge | Facts, entities, relationships and project knowledge | Versioned, provenance-bearing, expiry-aware |
| M3 — Profile and preferences | Owner-confirmed preferences, interaction choices and routines | Durable until changed, expired or deleted |
| M4 — Procedural skills | Approved skills, playbooks, tool-use lessons and workflows | Versioned; only active after validation/approval |
| M5 — Active objectives | Current goals, checkpoints, delegation and pending approvals | Durable until completed/cancelled/archived |
| Evidence vault | References to images, screen observations, audio transcripts and external evidence | Separate permission/retention rules; references, not unrestricted raw media |

The evidence vault is a governed evidence tier, not a reason to retain raw media indefinitely.

### C2. Memory record contract

Every record should support:
- Stable ID, tier/category, content or evidence reference.
- Source/provenance and originating conversation/task/agent.
- Created, updated, observed-at and valid-from/valid-until timestamps.
- Confidence and review status (pending, owner-approved, rejected, superseded).
- Sensitivity/classification and allowed audiences/agents.
- Grant/consent source where ambient context is involved.
- Version/supersedes links, correction history and expiry policy.
- Retention, deletion and audit metadata.

Contradictions must be represented as competing claims with provenance and validity windows, not silently overwritten. A new preference should supersede the active value while preserving the previous version until its retention/deletion policy says otherwise.

### C3. Retrieve-before-act protocol

Before any action that changes device, account, file, task, schedule, communication or external state, the central brain must:

1. Resolve the exact target and current objective.
2. Retrieve relevant profile preferences, prior decisions, active tasks, recent receipts, applicable skills and evidence.
3. Filter by sensitivity, grant, agent scope and freshness *before* ranking.
4. Check contradictions, superseded facts and expiry.
5. Confirm current capability health and policy/permission state from the live registry.
6. Build an action brief containing the retrieved facts, provenance, confidence and missing information.
7. Plan and authorize through the canonical task lifecycle.
8. Execute with idempotency/checkpointing where supported.
9. Verify the postcondition and persist the result or uncertainty.

Retrieval is mandatory for state-changing actions, not a reason to add latency to every greeting or simple factual response. Low-risk conversational replies can use the bounded relevant context path.

### C4. Memory confidence and trust

- Owner-confirmed entries outrank model guesses, but can still become outdated.
- Tool output is evidence about one observation, not automatically a durable fact.
- Ambient observations expire quickly and cannot silently become profile memories.
- Sensitive entries are excluded from model context even if pinned or approved, according to existing governance.
- Confidence must be calibrated from provenance and verification, not generated as an arbitrary model score.
- Agents receive filtered memory views and cannot broaden their own scope.

### C5. Consolidation, correction and deletion

- Consolidate episodic outcomes into semantic lessons only after verification.
- Preserve version history and explain why a memory changed.
- Support user correction, expiry, export and deletion across indexes, graph edges, cached prompts and derived summaries.
- Run consolidation on a bounded schedule/budget and provide an audit trail.
- On provider switch, retain the same canonical memory IDs and active objective references.

**Acceptance tests**
- A task reuses a saved user preference without asking again when it is still valid and authorized.
- A changed preference supersedes the active version while preserving the prior version/history.
- Expired or revoked ambient context is never used to justify an action.
- A specialist sees only its allowed memory categories and cannot access unrelated private context.
- A conflicting fact is surfaced with provenance rather than silently choosing one.
- Before a state-changing action, a test proves retrieval happened before dispatch and the action receipt records which memory/evidence IDs informed it.
- Deletion removes content and derived index/graph/cache references as specified by the deletion contract.

---

## Workstream D — Personality, Behaviour and Mood Dynamics

### D1. Stable identity and interaction contract

Personality is a consistent communication and collaboration style, not a fictional claim of consciousness. Keep:
- Stable identity, name, role and relationship to the owner.
- Clear, calm, direct and context-aware communication.
- Ability to explain uncertainty and admit mistakes.
- Adaptable verbosity and technical depth.
- Respect for the owner's chosen tone, language and accessibility preferences.
- Consistent values and safety boundaries across providers, voice and chat.

The identity/behaviour contract is provider-neutral and versioned. Provider prompts may render it, but must not own or redefine it.

### D2. Separate personality, behaviour policy and transient mood

These are different state domains:

- **Personality:** stable traits and communication preferences; changes only through an owner-approved profile update.
- **Behaviour policy:** deterministic rules for initiative, interruptions, verbosity, confirmation, follow-up and escalation. Policy code remains authoritative.
- **Mood dynamics:** short-lived presentation state inferred only from explicit interaction context and verified app/task events; it never changes permissions, safety thresholds, truthfulness or trading decisions.

Mood is optional and must default to neutral/calm when evidence is absent. Do not infer the owner's emotional or mental state from ambient signals. Never manipulate the user, manufacture urgency, guilt them, simulate jealousy/dependency or claim Orion has human feelings.

### D3. Mood state model

Use a small, inspectable state model such as `CALM`, `FOCUSED`, `ALERT`, `CELEBRATORY` and `REFLECTIVE`, with:
- Trigger and evidence reference.
- Confidence and expiry/decay.
- Current state and previous state.
- User override/disable control.
- Transition reason exposed when asked.

Examples:
- A verified urgent system failure may temporarily set `ALERT`.
- A completed milestone may permit a restrained `CELEBRATORY` response.
- A complex active task may set `FOCUSED`.
- No event or weak evidence remains `CALM`.

Mood can alter phrasing, pacing, greeting and notification priority only within fixed behaviour policy. It cannot suppress warnings, exaggerate confidence, initiate actions or alter capability access.

### D4. Behaviour continuity

- Same personality across typed chat, voice, notifications and delegated-work summaries.
- Recognize the active objective and recent conversation before greeting or interrupting.
- Use concise progress updates for long-running work; do not narrate every internal step.
- Ask one focused question when an essential ambiguity blocks safe execution.
- Avoid repeating confirmations already covered by a valid saved grant.
- Allow the owner to set initiative level, quiet hours, verbosity, humour, voice and proactive notification preferences.
- Keep all behaviour preferences versioned, reviewable and reversible.

**Acceptance tests**
- Switching providers does not change Orion's identity or interaction contract.
- Neutral context yields neutral/calm behaviour; mood decays and can be disabled.
- Mood cannot alter permission, confirmation, trading or safety decisions.
- The same task context produces coherent continuity across chat and voice.
- Orion never claims human consciousness, private feelings or observations it did not actually receive.
- Owner changes to personality preferences preserve the prior version and take effect consistently.

---

## Cross-workstream central-brain integration

The four workstreams must use these shared interfaces:

1. **Context Broker:** returns permissioned, minimized, freshness-tagged observations.
2. **Memory Gateway:** one retrieve/write/version/delete API with scope-aware filtering.
3. **Capability Registry:** live registration, setup, health, permission and grant state.
4. **Objective Coordinator:** canonical plan, task IDs, checkpoints, cancellation and recovery.
5. **Skill Registry/Factory:** versioned declarative skills, provenance and approval lifecycle.
6. **Repair Ledger:** diagnostics, proposals, tests, approvals, application and rollback receipts.
7. **Companion State:** stable identity/preferences plus bounded transient mood/behaviour state.
8. **Evidence Ledger:** source-linked proof for observations, actions and verified outcomes.

All services must expose bounded contracts and deterministic tests. The model proposes; policy code authorizes. The central brain owns interpretation and user-facing completion. Specialists return evidence and cannot independently mark the parent objective complete.

## Implementation order and dependencies

1. Audit existing classes and tests against this specification; reuse and extend existing stores/policies.
2. Complete canonical memory record/version/expiry and memory gateway contracts.
3. Implement retrieve-before-act in the central task coordinator and prove retrieval receipts precede dispatch.
4. Complete failure capture and persistent repair ledger using actual provider/tool/worker failure hooks.
5. Connect repair proposals to the existing restricted sandbox; prove test, owner approval, activation and rollback lifecycle.
6. Complete the ambient context broker, per-source grants, freshness/retention and proactive event routing.
7. Consolidate personality/behaviour preferences and transient mood into one provider-neutral companion state contract.
8. Connect all four workstreams to parent/child tasks, voice, MCP and the main conversation timeline.
9. Run cross-workstream integration tests, exact-head CI and the Redmi A3 Pro/HyperOS acceptance matrix.

Do not build a new database or coordinator merely to satisfy this specification. Extend the existing Room/SQLite, memory, task, capability and policy systems where they are already authoritative. If a store must be consolidated, define a migration and compatibility test first.

## Stage 4 exit gate — Living Companion

Stage 4 remains **IN PROGRESS** until all of the following are demonstrated on the exact PR head:

- [ ] One central brain coordinates chat, voice, tools, MCP, specialists and background execution.
- [ ] Objective → plan → authorization → execution → verification → persistence → report works end-to-end.
- [ ] Failure evidence enters a durable diagnostic/repair ledger; safe repair is tested and rollback is proven.
- [ ] Skill synthesis produces sourced, license-reviewed, sandbox-tested, owner-approved, versioned skills.
- [ ] Ambient awareness is independently permissioned, minimized, revocable, freshness-aware and honest about Android/HyperOS limits.
- [ ] Tiered memory has provenance, confidence, versioning, expiry, scope, correction and deletion semantics.
- [ ] Retrieve-before-act is enforced for state-changing operations and recorded in execution receipts.
- [ ] Personality is stable across providers and modalities; behaviour policy is deterministic and owner-controlled.
- [ ] Mood is bounded, transparent, optional and unable to change safety/authorization.
- [ ] Memory, context, repair, skills and companion state survive process restart and provider switching.
- [ ] Cross-agent privacy/scope tests pass; no agent can broaden its own access.
- [ ] CI passes on the exact branch head and the physical Redmi A3 Pro test matrix is recorded separately.

**Never mark this stage complete from architecture documents or unit tests alone.** Require integration evidence, real execution receipts, exact-head CI and physical-device results. Keep blocked device checks explicitly marked BLOCKED.

## Constraints carried forward

- Continue on `orion/autonomous-companion-roadmap-20261031`; preserve Claude's merged Trading Desk work and the existing stacked PR/release path.
- Keep `main` untouched by this roadmap work.
- Preserve the one-active-specialist-job constraint until concurrency is explicitly redesigned and tested.
- No background real-money orders. Real exchange orders remain separately risk-checked and explicitly confirmed.
- Do not create duplicate coordinators, memory stores, capability registries or execution runtimes.
- Do not promise unrestricted always-on monitoring on Android/HyperOS.
- Do not claim code, CI, APK or physical-device verification without current evidence.
