# Ultron architecture adoption for Orion

## Source and ownership

This migration studies [Kingijimzy/Ultron](https://github.com/Kingijimzy/Ultron), which GitHub identifies as a fork of [AbuZar-Ansarii/Ultron](https://github.com/AbuZar-Ansarii/Ultron). Neither the fork nor upstream exposes GitHub-recognised licence metadata or a root LICENSE file; the README's MIT badge alone is not a licence grant. Orion independently implements architecture patterns and equivalent capabilities. Do not copy substantial upstream implementation code unless the upstream owner confirms permission or a valid licence is supplied. Preserve attribution and review dependency licences independently.

Ultron is a Python desktop assistant. Orion is a native Android/Kotlin application with a managed Python runtime. Port architecture and reusable algorithms; adapt platform-specific behavior. Do not copy desktop APIs, paths, subprocess assumptions, background-thread models, or unsafe dynamic-import behavior into Android.

## Conflict audit first

See [ULTRON_ORION_CONFLICT_AUDIT.md](ULTRON_ORION_CONFLICT_AUDIT.md) for the source-level inventory and conflict decisions.

The audit found that Orion already has implementations for most of Ultron's advertised systems. A second unused `OrionCentralRuntime.Engine` would have created another execution owner without fixing the live entry points, so that provisional kernel and its isolated tests were removed from this branch.

Current ownership baseline:
- Live interaction and command execution: `Agent` entry points.
- Executive context: `OrionCentralBrain` and its existing `OrionSoulEngine` context provider.
- Built-in capability contracts: `ToolRegistry`; live eligibility: `OrionCapabilityDiscovery` / `CapabilityRegistry`.
- Last-mile execution: `OrionExecutionLoop` plus Agent's policy, recovery, evidence and verification boundary.
- Memory and retrieval: `MemoryStore`, `MemoryRanking`, `KnowledgeStore`, and the existing SQLite `OrionKnowledgeGraph`.
- Specialists and background work: `OrionSpecialistRuntime`, worker/job/lease stores, and `OrionRuntime`.
- Self-extension: the existing restricted extension draft/test/promotion lifecycle.

The goal is to make these existing owners coherent and remove duplicated prompt assembly, intent routing, progress ledgers and capability discovery—not to create replacement classes with the same names.

## Ultron capabilities and Orion adaptation

| Ultron subsystem | Existing Orion owner / target | Required adoption |
|---|---|---|
| Main runtime / unified prompt | Agent + OrionCentralBrain | Consolidate shared lifecycle across chat, live voice, command, resume and specialist turns. One goal/checkpoint/evidence contract. |
| Action loader / registry | ToolRegistry + OrionCapabilityDiscovery + CapabilityRegistry | Merge built-ins and verified enabled extensions through one live discovery adapter and one dispatch boundary. |
| Cerebro graph | OrionKnowledgeGraph (SQLite) + MemoryStore | Extend existing SQLite schema with explicit entity/relation edges, provenance, confidence, expiry and versioned semantic vectors. Migrate approved legacy facts. |
| Semantic retrieval | MemoryRanking + KnowledgeStore | Keep BM25 + TF-IDF; add normalized character-trigram similarity and deterministic hybrid ranking tests. |
| Session continuity | ChatHistoryStore, OrionConversationRepository, AgentCheckpointStore, OrionTaskCheckpointStore | Retrieve relevant history before decisions; reconcile in-flight actions before resuming; preserve context across provider/mode changes. |
| Dedicated coding planner | OrionPythonWorkspace, OrionPythonCapabilityExecutor, workspace patch/checkpoint and evidence components | Port Ultron Dev Agent's multi-file planning, dependency ordering, traceback mapping and bounded repair loop to the shared Orion workspace. |
| Self-extension | SelfExtendingExtensionStore / Policy and extension tools | Preserve draft → static validation → isolated sandbox tests → source-hash-bound verification → approval/promotion → rollback. Never import generated code into the app process. |
| Parallel agents | OrionSpecialistRuntime and worker/job/lease infrastructure | Port supervised mission patterns, not unbounded daemon threads. Every worker shares parent objective/checkpoints and returns evidence. |
| Coding repair / reconnect | AgentCheckpointStore, OrionRepairLifecycle, OrionRecoveryPlanner | Resume durable sessions and verify in-flight mutations before retry; repair from actual diagnostics and test output. |
| Ambient awareness / Chronos | Android sensor, notification, app/device/location context providers and WorkManager workers | Port time-aware summaries and proactive scheduling; do not claim desktop window/process telemetry or override Android thermal/battery policy. |
| Web Autopilot | Orion Browser and research capabilities | Keep browser actions in the same turn context, capability registry and evidence ledger. |
| Proactive actions | OrionProactiveWork, reminder/routine workers, OrionRuntime | Persist schedules, deduplicate, support cancellation and respect owner-configured cadence and Android background limits. |

## Architecture rules

1. Every user turn enters a shared interaction contract, regardless of whether it came from chat, live voice, command, notification or resume.
2. Context is assembled once from transcript/history, active objective, approved memory, project workspace, device observations, capability state and prior evidence. Avoid injecting the same memory, tool inventory, worker ledger or app map multiple times.
3. One capability registry describes available tools, schemas, prerequisites, platform, side effects and verification requirements.
4. All actions pass through the existing policy and dispatch boundary. A model decision or successful tool invocation is not completion evidence.
5. One objective/checkpoint ledger owns task progress. Specialists return results to the parent objective; they do not independently mark the parent complete.
6. Memory retrieval occurs before planning and acting. Facts retain provenance, confidence, sensitivity, review status and temporal validity. Corrections and contradictions supersede old facts rather than silently accumulating.
7. Extensions are not executable until isolated tests pass against the exact source hash and the configured owner policy permits promotion.
8. Background work must be durable, bounded, cancellable, battery-aware and recoverable after process death.
9. Platform permission, external authentication and consequential-action approval remain real requirements. Do not ask for duplicate consent where permission is already granted, and do not bypass an actual required approval.

## Known risks to fix

- `Agent.kt` remains a large interaction/execution class. Extract components only as each migration slice has parity tests; avoid a risky rewrite.
- `chatSystem()` and `OrionCentralBrain.contextFor()` currently collect overlapping memory, application, capability and worker context. Consolidate to a typed, measured context snapshot.
- Agent intent routing, task orchestration, objective graphs, checkpoints and specialist envelopes overlap. Clarify ownership and prevent divergent status.
- Orion's SQLite graph currently indexes memory nodes to lexical term nodes; it is not yet a full Cerebro entity/relation graph with multi-hop semantic scoring.
- Orion already has BM25 + TF-IDF retrieval; character trigrams are the specific retrieval feature to port.
- Built-in tools and saved extensions are not yet a single discovery surface.
- Ultron's Prometheus implementation uses heuristic source-pattern checks and dynamic Python imports; its Drone Swarm uses daemon threads and recurring loops; Chronos reads desktop OS/window/process telemetry and includes non-portable performance claims. These are design references, not safe drop-in Android implementations.

## Delivery sequence

### Slice 1 — conflict removal and shared contracts
- Keep current live Agent paths stable.
- Remove the unused parallel runtime proposal.
- Add contract tests proving chat, live voice, command, resume and specialist paths share the same context, objective, registry and dispatch contracts.
- Consolidate duplicated prompt/context assembly and establish one typed context snapshot.

### Slice 2 — capability discovery and dispatch
- Audit all capability registries, tool lists, shortcuts and extension execution paths.
- Merge built-in and verified extension capabilities into one discovery surface.
- Ensure every dispatch reaches the same policy, cancellation, evidence and verification boundary.
- Remove duplicate routing only after parity tests pass.

### Slice 3 — Cerebro graph and retrieval
- Extend the existing SQLite graph with versioned entity/relation/vector tables.
- Migrate approved facts with provenance and trust metadata.
- Port Ultron's TF-IDF/character-trigram semantic matching into the existing hybrid ranker.
- Test fuzzy entity matching, multi-hop retrieval, contradictions, expiry and memory deletion.

### Slice 4 — coding, specialists and ambient awareness
- Complete multi-file coding plan → edit → compile → test → diagnose → bounded repair on Orion's shared workspace.
- Complete supervised parallel workers with leases, checkpoints, cancellation, bounded retries and parent-owned completion.
- Add Android-native Chronos awareness and proactive scheduling.
- Complete browser and self-extension flows through the same registry and evidence model.

### Slice 5 — hard acceptance
- Exact-head Android build and unit/integration tests.
- End-to-end tests for context continuity, memory, tool discovery, browser, Python, GitHub, multi-file coding, worker resume, cancellation and repair.
- Physical Android acceptance before calling the migration complete.

## Definition of done

A feature is not complete because a class exists, a tool was called, or a model says it worked. It is complete only when exact-head build passes, automated tests exercise the real wiring, runtime evidence proves the outcome, and Android behavior is accepted on device. Keep the migration on a dedicated branch; do not merge a red or unverified build.
