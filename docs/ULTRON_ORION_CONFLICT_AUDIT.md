# Ultron → Orion conflict and reuse audit

Audit date: 2026-11-05
Repositories: [Kingijimzy/Ultron](https://github.com/Kingijimzy/Ultron) → [Kingijimzy/phoneai](https://github.com/Kingijimzy/phoneai)

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
| Relationship graph | `OrionKnowledgeGraph` (SQLite), `KnowledgeGraph` / `GraphPolicy` | `KnowledgeGraph` is a compatibility view derived from approved MemoryStore records, not an independent trusted store. SQLite currently indexes memory-to-term edges; extend this schema for explicit entities/relations and multi-hop retrieval. |
| Semantic retrieval | `MemoryRanking.hybridRank`, `KnowledgeStore` | BM25 + TF-IDF hybrid retrieval already exists. Ultron's character-trigram matching is a real gap; port it into the existing ranker with deterministic tests rather than introducing another vector store. |
| Session continuity | `ChatHistoryStore`, `OrionConversationRepository`, `AgentCheckpointStore`, `OrionTaskCheckpointStore`, `OrionContextualIntentInterpreter` | Durable history search and resumable checkpoints exist. Validate shared use by every interaction mode and reconcile in-flight actions before retry. |
| Coding agent | `OrionPythonCapabilityExecutor`, `OrionPythonRuntime`, `OrionPythonWorkspace`, `OrionWorkspacePatchStore`, task/evidence/recovery components | The managed Python workspace and repair primitives exist. Ultron's multi-file planner/repair loop should be adapted to this workspace; do not import desktop paths, subprocess assumptions or its separate project directory. |
| Parallel agents | `OrionSpecialistRuntime`, `OrionBuiltInSpecialists`, `OrionWorkerDispatcher`, `OrionWorkerJobStore`, `OrionWorkerLeaseStore`, `OrionSpecialistWorker`, `OrionDistributedExecution` | Supervised worker/lease/checkpoint infrastructure already exists. Port useful mission scheduling and reporting patterns, not Ultron's unbounded daemon-thread model. |
| Self-extension | `SelfExtendingExtensionStore`, `SelfExtendingExtensionPolicy`, `extension_*` tools, restricted Python sandbox | A constrained draft/test/enable/run lifecycle exists. Do not port Ultron's direct import-and-hot-load path or heuristic-only safety check into the Android process. |
| Ambient awareness | `OrionAppAwareness`, `OrionSensorContextSampler`, `OrionSensorContextStore`, `OrionNotificationListener`, device/location/app-usage providers, reminder workers | Android-native awareness exists. Port only portable scheduling/prediction logic; desktop window/process/power-plan telemetry is not Android-compatible. |

## Conflicts and concrete issues found

1. **Duplicate central runtime proposal:** the newly added, unused `OrionCentralRuntime.Engine` would create a second execution owner. Removed from this branch before further migration.
2. **Prompt/context duplication:** ordinary chat builds `chatSystem()` and then appends `OrionCentralBrain.contextFor()`; both collect overlapping memory, application, capability and worker information. Measure prompt size/latency and consolidate into one typed context snapshot.
3. **Multiple orchestration concepts:** Agent intent routing, `OrionTaskOrchestrator`, objective graph/checkpoint logic and specialist task envelopes overlap. Define clear ownership: Agent is the current interaction adapter; central brain owns context/decision; one objective ledger owns progress; one capability registry owns discovery; one dispatch boundary owns execution.
4. **Graph gap:** Orion's SQLite graph currently indexes memory nodes to lexical term nodes; it does not yet provide Ultron Cerebro's full entity-relation graph with persisted semantic vectors and multi-hop scoring. Extend the existing SQLite database with versioned schema migrations; do not create a second database.
5. **Retrieval gap:** Orion already has BM25 + TF-IDF. Add normalized character 3-gram features and tests to improve fuzzy matching, including typos and partial entity names.
6. **Dynamic registry gap:** built-in tools and saved extensions are exposed through different mechanisms. Add an adapter that merges verified enabled extensions into capability discovery while retaining one dispatch/policy boundary.
7. **Coding-agent gap:** ensure multi-file plans, dependency ordering, compile/test output, traceback-to-file mapping, bounded repairs and workspace checkpoints all use Orion's managed workspace and evidence ledger.
8. **Worker supervision:** ensure every worker has a persisted owner objective, lease, cancellation, bounded retries, result receipt and independent verification. Never let a drone independently mark the parent objective complete.
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
9. Run exact-head CI, integration tests, and physical Android acceptance before merge.

## Validation facts

- PR #208 commit `2bc44f58e9080fcdaa272c05de93c4dc6832756c` had a completed successful `Build APK` workflow run (run 42018799653, job 127215984279).
- That workflow proves the foundation branch built and its configured tests passed at that commit. It does not prove the proposed kernel was wired to live entry points.
- No changes from this migration have been merged into `main`.
