# Ultron → Orion conflict and reuse audit

Audit date: 2026-11-05
Repositories: [Kingijimzy/Ultron](https://github.com/Kingijimzy/Ultron) → [Kingijimzy/phoneai](https://github.com/Kingijimzy/phoneai)

## Source provenance and licensing

GitHub identifies `Kingijimzy/Ultron` as a fork of `AbuZar-Ansarii/Ultron`. Neither repository currently exposes GitHub-recognised licence metadata or a root `LICENSE` file. The README's MIT badge is not itself a licence grant. This migration independently implements architecture patterns and equivalent capabilities; it does not assume upstream code is owner-authored or licensed for copying. Do not copy substantial upstream implementation code unless the upstream owner confirms permission or supplies a valid licence. Preserve attribution and review third-party dependency licences separately.

## Decision

Do not copy Ultron subsystems into Orion as parallel implementations. Orion already has substantial implementations of nearly every requested capability. The migration must consolidate and improve those implementations, porting Ultron algorithms only where Orion has a measurable gap.

The first proposed `OrionCentralRuntime` kernel was not connected to the live app and duplicated responsibilities already split across `Agent`, `OrionCentralBrain`, `OrionSoulEngine`, `OrionTaskOrchestrator`, and `OrionExecutionLoop`. It is removed from this migration branch. The existing live path remains the compatibility baseline until a replacement is proven across chat, voice, command, resume and specialist entry points.

## Existing Orion ownership map

| Responsibility | Existing Orion owner(s) | Audit result / migration action |
|---|---|---|
| User-turn runtime | `Agent.runConversation`, `Agent.runLiveConversation`, `Agent.run`, `Agent.runInternal` | These are live entry points today. Consolidate their shared lifecycle; do not add a second runtime beside them. |
| Executive context | `OrionCentralBrain.contextFor`, `OrionSoulEngine.contextFor` | Already integrated into chat/voice/specialist prompts. Reduce duplicate context assembly and make one typed snapshot authoritative. |
| Tool registry and discovery | `ToolRegistry`, `OrionCapabilityDiscovery`, `CapabilityRegistry`, `OrionTaskOrchestrator` | Built-in tools have a canonical registry and live availability checks. Extension tools need a single registry adapter, not another registry. |
| Last-mile dispatch | `OrionExecutionLoop`, `Agent.executeWithRecoveryGuard`, `Tools` | Real dispatch already passes through registered-tool checks and owner policy/evidence logic. Preserve this boundary while moving orchestration out of the monolith. |
| Long-term memory | `MemoryStore`, `MemoryGovernancePolicy`, `OrionMemoryCapabilityExecutor`, `MemoryAutoIndexer` | Durable phone-local memory and retrieve-before-act already exist. Improve automatic extraction, correction and cross-session retrieval without bypassing provenance/approval. |
| Relationship graph | `OrionKnowledgeGraph` (SQLite), `KnowledgeGraph` / `GraphPolicy` | The existing SQLite graph now stores extracted subject→relation→object entity edges with confidence and memory provenance, alongside lexical term edges. Retrieval expands a bounded two rounds; callers still reapply current memory governance. |
| Semantic retrieval | `MemoryRanking.hybridRank`, `KnowledgeStore` | Character 3-gram similarity has been added to the existing lexical/hybrid ranker with typo and unrelated-term tests. No second vector store is introduced. |
| Session continuity | `ChatHistoryStore`, `OrionConversationRepository`, `AgentCheckpointStore`, `OrionTaskCheckpointStore`, `OrionContextualIntentInterpreter` | Durable history search and resumable checkpoints exist. Validate shared use by every interaction mode and reconcile in-flight actions before retry. |
| Coding agent | `OrionPythonCapabilityExecutor`, `OrionPythonWorkspace`, `OrionProjectWorkspace`, `OrionCodingPlanner`, `OrionRuntimeCapabilityExecutor` | Durable multi-file plans are now wired through `project_plan`, `workspace_write` and `project_verify`; dependency order, read-back hashes, fixed language tests, owner-approved test execution and a two-repair limit are enforced. Exact-head CI passed for the coding-planner integration. |
| Parallel agents | `OrionSpecialistRuntime`, `OrionBuiltInSpecialists`, `OrionWorkerDispatcher`, `OrionWorkerJobStore`, `OrionWorkerLeaseStore`, `OrionSpecialistWorker`, `OrionDistributedExecution` | Durable WorkManager workers now allow up to three active analysis/research jobs, with persisted parent linkage, cancellation, result staging and process-death review. Workers cannot execute phone actions or close parent objectives. Exact-head CI and concurrency tests are pending on this latest slice. |
| Self-extension | `SelfExtendingExtensionStore`, `SelfExtendingExtensionPolicy`, `extension_*` tools, restricted Python sandbox | A constrained draft/test/enable/run lifecycle exists. Do not port Ultron's direct import-and-hot-load path or heuristic-only safety check into the Android process. |
| Ambient awareness | `OrionAppAwareness`, `OrionSensorContextSampler`, `OrionSensorContextStore`, `OrionChronosAwareness`, `OrionDecisionScheduler`, `OrionNotificationListener` | Android Chronos now uses Accessibility window events only when App usage and a separate background Chronos grant are active. It stores coarse app category/focus time locally, excludes finance/credential apps, retains no package names or screen text, expires after two hours and never triggers device actions. Exact-head CI and device acceptance are pending. |

## Conflicts and concrete issues found

1. **Duplicate central runtime proposal:** the newly added, unused `OrionCentralRuntime.Engine` would create a second execution owner. Removed from this branch before further migration.
2. **Prompt/context duplication:** ordinary chat builds `chatSystem()` and then appends `OrionCentralBrain.contextFor()`; both collect overlapping memory, application, capability and worker information. Measure prompt size/latency and consolidate into one typed context snapshot.
3. **Multiple orchestration concepts:** Agent intent routing, `OrionTaskOrchestrator`, objective graph/checkpoint logic and specialist task envelopes overlap. Define clear ownership: Agent is the current interaction adapter; central brain owns context/decision; one objective ledger owns progress; one capability registry owns discovery; one dispatch boundary owns execution.
4. **Graph boundary:** the SQLite graph now persists entity-relation edges and bounded multi-hop recall. It is not a neural embedding store; semantic ranking remains lightweight lexical/trigram retrieval and memory governance remains authoritative.
5. **Retrieval:** BM25/TF-IDF plus normalized character 3-gram similarity is implemented and tested; continue tuning only against measured retrieval failures.
6. **Dynamic registry:** verified enabled extensions are merged into live capability discovery and are advertised only when the shared `extension_run` adapter is available; retain one dispatch/policy boundary.
7. **Coding agent:** durable multi-file planning, dependency gating, write receipts, read-back verification, fixed test commands and bounded repair are wired. Remaining work is traceback-to-file mapping and full multi-language acceptance.
8. **Worker supervision:** three concurrent durable specialist jobs are now allowed. Continue testing concurrent provider calls, parent cancellation races, slot release, result delivery and restart reconciliation. Never let a drone independently mark the parent objective complete.
9. **Ultron code quality constraints:** its Prometheus module uses heuristic source-pattern checks and dynamic Python imports; its Drone Swarm uses daemon threads and can run recurring loops; Chronos reads desktop OS/window/process telemetry and includes non-portable performance claims. These are design references, not safe drop-in Android implementations.

## Required migration order

1. Keep the current live Agent paths stable; remove the unused parallel kernel.
2. Build a call-site inventory and add contract tests proving chat, live voice, command, resume and specialist work use the same context, objective, registry and dispatch contracts.
3. Consolidate duplicate prompt/context assembly and define typed context snapshots.
4. Extend the existing SQLite graph with versioned entity/relation/vector tables; migrate approved facts and keep provenance, sensitivity and expiry.
5. Add Ultron's TF-IDF/trigram retrieval to the existing hybrid ranker.
6. Unify verified extension discovery with the existing capability registry.
7. Complete the multi-file coding planner/repair loop on Orion's workspace.
8. Complete worker supervision and Android-native Chronos awareness.
9. Complete exact-head CI for the latest Chronos/parallel-worker slice, run interaction-mode integration tests, and perform physical Android acceptance before merge.

## Validation facts

- PR #208 foundation commit `0d330453175b15501466f6f34fb8ef2d9e760c01` passed `Build APK` (run 42027965545).
- Coding planner + capability routing commit `a2c6acc73ec41b89ad0c9087934642a1ed1f0709` passed `Build APK` and all configured unit tests (run 42037485726).
- Chronos and three-worker concurrency changes are staged separately and require their own exact-head CI before they can be described as verified.
- No changes from this migration have been merged into `main`.
