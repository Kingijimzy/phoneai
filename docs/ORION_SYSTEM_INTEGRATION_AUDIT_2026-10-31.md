# Orion system-wide integration audit — 2026-10-31

## Scope and evidence

Audited the active integration branch `orion/close-direct-tool-bypasses`, not `main`. The branch is the head of draft PR #132 and is stacked on the Python executor branch (#131). At the start of this audit, `main` had 310 tree entries while the integration branch had 437. The integration branch is not merged into `main`.

Source inspection covered the Agent, capability registry/adapters, runtime dispatcher, all capability executor families, task orchestration, objective graph, checkpoint store, evidence ledger, central-brain context, memory stores, permissions, owner/autonomy settings, and test suite.

## Executive finding

Orion has broad capability coverage and a real shared dispatch boundary. Its largest weakness is not a lack of registered tools. It is that the central interactive Agent still owns most task control-flow, while several alternate paths have different continuity and evidence behavior. The app has shared context, but not one canonical task/evidence record across every surface.

## 1. Orchestration paths

There is one primary interactive tool-planning loop, but at least seven distinct execution/control paths:

1. **Talk auto-routing** — `Agent.runConversation`. It can route directly to Command for recognized actionable requests; otherwise it asks the talk model to emit `ACTION` before entering the command loop.
2. **Command/tool loop** — `Agent.run` → `runInternal`. This is the main planner/executor loop and owns task steps, retries, checkpoints, objective receipts and completion decisions.
3. **Live voice conversation** — `runLiveConversation`. Explicitly conversation-only: it does not expose or dispatch phone tools. Voice therefore is not yet a Jarvis-equivalent execution surface.
4. **Specialist agents** — `AgentRunner`. Specialist profiles make their own model calls and inject selected memory, task-board and research/trading context. They are not the same tool-execution loop as the main Agent.
5. **Deterministic shortcut paths** — fast intents, local commands and direct weather/news/sports/GitHub/web/quote lookups inside `runInternal`. These save model calls, but not all use the same checkpoint/objective lifecycle.
6. **Background runtime** — `OrionRuntime` persists and reclaims queued work. Soul watcher and standing-order work run through this background coordinator, separate from the foreground Agent turn.
7. **Recorded routine replay** — a deterministic replay state machine with its own routine checkpoint and screen-fingerprint checks. Its phone actions are routed through the guarded dispatch callback.

`OrionCentralBrain` provides a shared executive context. `OrionTaskOrchestrator` builds request-time goal/capability/readiness context and blocks unavailable dispatches. `OrionExecutionLoop` is a last-mile registered-tool/cancellation boundary. These are useful shared components, but the task orchestrator does not own the full plan/execute/resume lifecycle; that still lives mainly in `Agent.runInternal`.

## 2. Registered tools and executor ownership

- **137 registered tool contracts** in `ToolRegistry`.
- **137 unique adapter routes** in `OrionCapabilityAdapters`.
- **0 missing adapter mappings** and **0 duplicate backend owners**.
- Runtime support is explicitly audited by `OrionRuntimeCapabilityExecutor.supports`; `OrionCapabilityAdaptersTest.everyRegisteredRouteHasAConcreteRuntimeImplementation` fails if a route lacks a concrete implementation.
- There are 16 active backend families: Shell, Python, Files, Browser, MCP, Device, Skills, Integrations, Communication, Memory, Tasks, Research, Utilities, Trading read-only, Trading desk and Gaming. The `CORE` enum value has no route.
- Thirteen dedicated family executor classes own capability families; Shell, Files and Browser are concrete branches in `OrionRuntimeCapabilityExecutor`.

No registered tool is structurally orphaned in this branch. This proves code ownership and dispatchability, not that every tool has been exercised on the physical phone or that every external connector is configured.

## 3. Direct paths that do not share the complete lifecycle

The normal model-planned tool path is:

`runInternal` → `executeWithRecoveryGuard` → `OrionExecutionLoop.dispatch` → `exec` → `OrionCapabilityAdapters.dispatch` → `OrionRuntimeCapabilityExecutor.execute` → family executor.

The guarded path applies objective dependency checks, request-time orchestration availability, cancellation, accessibility disclosure, pack and per-tool permissions, dispatch safety, approval, verification, durable checkpoint step recording and objective evidence.

Important exceptions:

- The deterministic `research()` helper manually checks pack/tool authorization, orchestrator availability, cancellation, request scope and preflight, then calls `exec()`. It still uses the adapter and writes the evidence ledger, but it does **not** pass through `executeWithRecoveryGuard` / `OrionExecutionLoop`, and its early-return shortcut path does not get the normal checkpoint `beginStep/recordStep` and objective receipt/completion handling. This is a real lifecycle split, even though the read itself is policy-checked.
- The specialist `AgentRunner` makes direct model calls with contextual data; it is not a general registered-tool executor.
- Live voice intentionally has no tool dispatch path.
- Background runtime work has its own queue and recovery rules rather than being a foreground Agent tool-loop continuation.

Local fast commands, saved-routine gestures, vision-driven actions, and ordinary model-planned tools use the shared guarded dispatch callback.

## 4. Memory, tasks and evidence

**Partially shared, not one canonical store.**

The central brain assembles provider-neutral context from multiple durable stores, including conversation history, memory/lessons, decision history, task-board records, execution checkpoints, objective graphs, experience lessons and route memory. Main Agent and specialist agents can read overlapping stores.

However, these stores have different identifiers and persistence owners:

- `TaskStore`: user-facing task definitions, integer IDs, JSON file.
- `AgentCheckpointStore`: one resumable execution checkpoint, UUID task ID, AtomicFile.
- `OrionObjectiveGraph`: objective IDs scoped to the active chat session, stored in SharedPreferences.
- `OrionEvidenceLedger`: dispatch events with a hashed task ID and argument fingerprint, stored separately.
- `ChatHistoryStore`: transcript and objective evidence receipts.
- `MemoryStore`, `ExperienceStore`, `AgentLessons`, `KnowledgeGraph`, `OrionDecisionHistoryStore`, and `RouteMemoryStore`: separate memory/learning stores.

The context layer joins these stores for reasoning, but their writes and identifiers are not transactionally unified. A task-board record is not an execution receipt; an evidence event is not a completed task; and a saved objective graph is not the same thing as a resumable checkpoint.

## 5. Permission settings that affect execution

The source has multiple real enforcement layers:

- **Capability packs** — `AgentPolicy.enabled` and `ToolGate.preflightDenial` block tools when their pack is off.
- **Per-tool permission** — `ToolPermissions` ALLOW/ASK/DENY and Careful/Balanced/Autonomous/Read-only presets are consulted at dispatch. `ask_user`, `wait` and `time` are deliberately always allowed.
- **Agent policy mode** — ASK, PLAN, ALLOWLIST and TRUSTED session mode feed `GatePolicy`; trusted sessions are time-bounded and pack-scoped.
- **Autonomy mode** — TALK hard-blocks write tools; ASSIST uses current policy; AUTOPILOT permits configured multi-step execution while retaining required confirmations; BUILDER keeps extension activation governed by approval. Step, AI-request and retry budgets are clamped and enforced.
- **Android/runtime prerequisites** — screen-access disclosure, notification access, contacts, location, Termux readiness, MCP endpoint configuration/pairing/scopes, connector authentication and Android OS permissions are checked by discovery or the relevant executor.
- **Owner dials** — recovery attempts/backoff and Python profile size limits are read by execution code; verified extension auto-activation remains a separate owner-controlled decision.

These layers overlap. That is safe but makes it difficult to understand why a tool is blocked unless the UI reports the exact winning gate. Capability discovery explicitly models notification access, contacts, location, Termux and MCP setup; other permissions are checked later by their actual executors.

## 6. Objective and task ID lifecycle

- A new objective can be initialized by `runConversation` as a one-node graph with ID `main`.
- For multi-step planning, the model proposes `objective_graph` nodes and later supplies `objective_id` for each tool step.
- `OrionObjectiveGraph` validates IDs/dependencies, persists by active chat session, enforces prerequisite status, and accepts completion only from an exact successful evidence receipt in the same session.
- `AgentCheckpointStore.begin` creates a separate UUID task ID; each step is checkpointed before execution and recorded afterward.
- The evidence ledger associates dispatches with the checkpoint UUID (hashed for storage).
- User-visible Task Centre tasks have separate integer IDs in `TaskStore`.

**Confirmed bug fixed in this branch:** the dispatcher previously accepted any nonblank model-supplied `objective_id` before checking the graph. A one-node graph named `main` could therefore reject a model alias such as `calc_write` with “Unknown objective ID”. The shared dispatch boundary now resolves an unknown/blank ID to the only node when and only when the graph has exactly one node. Multi-node graphs still require an exact ID and fail closed otherwise. Regression tests cover both cases.

The remaining design issue is the number of separate IDs/stores and the fact that deterministic research shortcuts do not complete the checkpoint/objective receipt lifecycle.

## 7. Feature maturity

**Implemented in source with concrete routes:** device/accessibility actions, browser automation, shell/files, constrained Python and extension sandbox, research/news/weather/sports/GitHub public reads, memory and knowledge operations, task records/checkpoints, MCP, Telegram/SMS/dial intents, utilities, trading research/paper operations, gaming notes/focus, skills and background runtime queue.

**Partially integrated:** shared central context, memory/learning, objective graph + checkpoint continuity, specialist-agent coordination, background standing orders, capability discovery and recovery. The components exist, but cross-surface state transitions are not uniformly driven by one orchestrator.

**Unavailable by design today:** hands-free live voice cannot execute tools. It explicitly tells users to use main chat for phone actions.

**Potentially unconnected/placeholder packs:** `remote_computer`, `proactive` and `self_test` are declared in `AgentPolicy.packs`, but the audited `ToolRegistry`/tool-to-pack table does not provide a corresponding dedicated tool family for those pack IDs. Their actual end-user workflows need a separate wiring audit before they are described as working capabilities.

## 8. Tests and end-to-end proof

The integration branch contains 126 Kotlin test files under `app/src/test` and no `androidTest` tree. The suite includes useful Robolectric integration coverage for continuity, runtime persistence, adapter ownership, objective graph rules, permissions and checkpoint recovery.

The CI workflow builds the pinned runtime, runs restricted-Python checks, runs Kotlin unit tests, assembles a debug APK, validates its package/signature and uploads an artifact.

This is strong source/unit/build validation, but **not proof of complete end-to-end operation on the Redmi**. The continuity integration test composes transcript, decision, graph and checkpoint stores; it does not run the live model through every executor. Adapter tests verify route ownership and support declarations; they do not exercise every external service or real Android action. There are no instrumentation tests and physical-device verification remains outstanding.

## 9. Why Orion can feel slow or repetitive

Two source-level contributors stand out:

1. `ToolRegistry.promptSection` deliberately injects all 137 full tool contracts on every turn. This avoids hiding capabilities from the model, but increases prompt size and selection burden even for simple questions.
2. For actionable requests not recognized by deterministic routing, `runConversation` first calls the Talk model to decide whether to emit `ACTION`, then invokes the Command planner. Tool tasks may then require further model turns, and search summarization can add another model call. The shared AI-request budget is 15 by default.

The central brain also adds up to 16,000 characters of executive context, including up to 5,200 characters from Soul Engine context, transcript evidence, objective graph, decisions, checkpoint and learned experience. This improves continuity but can increase latency and token usage. Live voice has a separate, tool-disabled conversation path.

## 10. Recommended engineering order

1. Finish routing every deterministic shortcut through one managed task lifecycle, including checkpoint steps and objective evidence.
2. Unify the task identity model: one stable task/session ID should link Task Centre record, checkpoint, objective graph and evidence ledger.
3. Make live voice use the same intent router and guarded execution loop, with hands-free confirmation behavior for consequential actions.
4. Give specialists a bounded way to request registered capabilities through the central dispatcher instead of independent model-only paths.
5. Replace the all-137-full-contract prompt with a compact complete capability index plus dynamically selected full contracts, preserving discoverability and dispatch-time enforcement.
6. Add real Android instrumentation/device smoke tests for the critical vertical workflows: research → save memory → reminder; file save → Python execution; screen perception → action → verification; pause → process death → resume; voice request → guarded action.

## Current status

The objective-ID fix and regression tests are committed to the PR #132 integration branch. The new exact-head CI run must pass before the code change is considered build-verified. No merge to `main` and no physical-device success is claimed by this audit.
