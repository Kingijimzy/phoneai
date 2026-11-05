# Ultron architecture adoption for Orion

## Source and ownership

This migration is based on the owner's sibling repository, [Kingijimzy/Ultron](https://github.com/Kingijimzy/Ultron). Both repositories are owned by Kingijimzy. Ultron currently has no root LICENSE file, so this is an owner-authorized internal port, not a claim that the source is permissively licensed for third parties. Preserve source attribution in adapted modules and review third-party dependency licenses independently.

Ultron is a Python desktop assistant. Orion is a native Android/Kotlin application. The migration ports runtime architecture and reusable logic, not desktop-only APIs or Python modules that cannot execute in Android. Python-native Ultron capabilities will be evaluated for Orion's managed Python/Alpine worker.

## Non-negotiable runtime design

One central runtime owns each user turn and objective. Chat, voice, notifications and background events are input surfaces into the same runtime. No surface gets a private planner or alternate tool dispatcher.

Canonical flow:
1. Perceive request, transcript, active objective, checkpoint and device observations.
2. Resolve context from conversation, project state, memory graph and prior evidence.
3. Decide whether to converse, clarify, research or act.
4. Build or continue an explicit objective plan with dependencies and success conditions.
5. Discover capabilities from one live registry and typed contracts.
6. Apply owner-configured action policy at the dispatch boundary.
7. Execute through the shared runtime and append evidence.
8. Verify outcomes independently; a tool response is not proof of success.
9. Replan from observed failure with bounded recovery; never repeat identical failed calls blindly.
10. Persist checkpoint, memory observations, decision provenance and a user-readable receipt.

## Ultron features to port

| Ultron subsystem | Orion implementation target | Porting rule |
|---|---|---|
| Main runtime / unified prompt | OrionCentralRuntime and shared Agent entry points | Single runtime, provider-neutral |
| Action loader / registry | Typed Kotlin capability registry | Dynamic discovery; no hardcoded page-specific dispatch |
| Cerebro graph | Persistent entity/relation/fact store | Stable vector model, provenance, conflict resolution and migrations |
| Chronos | Android event and environment observation adapters | Android permissions and battery-aware scheduling |
| Prometheus | Skill synthesis lifecycle | Generate, inspect, isolated test, verify, approve/promote, version and rollback |
| Dev Agent | Shared-workspace coding workflow | Plan files, edit, compile, test, repair and review diff |
| Drone Swarm | Centrally supervised specialist workers | Shared objective/checkpoints; workers cannot own final state |
| Web Autopilot / Browser | In-chat browser and research capabilities | Same context and evidence ledger |
| Proactive actions | Android WorkManager/foreground-service adapters | Persisted jobs, cancellation and owner-configured cadence |

## Existing Orion baseline to consolidate

Agent.kt is 3,398 lines on the inspected main head and contains intent policies, context assembly, chat and live-chat paths, command execution, recovery, evidence, tool execution and verification. This concentration, together with separate orchestration and capability systems, creates maintainability and routing risk.

OrionTaskOrchestrator.kt is currently a small prompt/dependency helper, not the sole runtime. This migration must make the central runtime authoritative and progressively move behavior out of Agent.kt; do not add a second competing coordinator.

Existing checkpoint, approval, owner settings, capability discovery, Python runtime, evidence ledger and Android-specific features are retained only where they integrate through the central runtime. Do not silently remove user controls or report unsupported platform actions as available.

## Delivery sequence

### Slice 1 — central runtime foundation
- Define typed turn/context/objective/step/capability/evidence contracts.
- Define one execution lifecycle with phase events, dependency ordering, dispatch policy, verification, bounded replanning and memory receipt.
- Add deterministic tests proving verified completion, failed verification and dispatch blocking.
- Wire all existing chat/voice/resume entry points into this runtime before migrating specialist systems.

### Slice 2 — capability registry and dispatch consolidation
- Inventory current registries, tool dispatchers and shortcut paths.
- Create one registry with schemas, prerequisites, side-effect classification, platform, health and verifier metadata.
- Route every capability invocation through the same policy and evidence boundary.
- Remove duplicate dispatch only after parity tests pass.

### Slice 3 — context and Cerebro memory
- One context resolver for recent conversation, older history, active objective, project files, user memory and knowledge graph.
- Durable graph schema with stable embeddings and deterministic vocabulary/model versioning.
- Automatic extraction, deduplication, confidence, provenance, temporal validity, correction and contradiction handling.
- Retrieve before decisions and actions; test follow-ups and long conversations.

### Slice 4 — Ultron specialist capabilities
- Android-aware Chronos observations.
- Dev Agent multi-file plan/execute/test/repair on shared workspace.
- Drone workers supervised by one objective owner.
- Prometheus skill generation with isolation, tests, promotion and rollback.
- Web Autopilot, reminders and proactive workflows using the same runtime.

### Slice 5 — hard acceptance
- Kotlin unit tests, Android build, Python runtime tests and exact-head CI.
- End-to-end tests for follow-up context, memory, tool choice, browser, Python, GitHub, coding, recovery, interruption, background resume and Android capability outcomes.
- Physical-device acceptance before calling the migration complete.

## Definition of done

A feature is not complete because its class exists, a tool was called, or a model says it worked. It is complete only when exact-head build passes, automated tests exercise real wiring, runtime evidence proves the outcome, and Android behavior is accepted on device. Keep migration on a dedicated branch and do not merge a red or unverified build.
