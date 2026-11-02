# Contextual intent and orchestration design

## Purpose

Orion must understand the user's requested outcome and the conversation it belongs to before choosing a capability. A word such as `Python`, `weather`, `Telegram`, `GitHub`, `browser`, or `test` can be the subject of an engineering task; it is not, by itself, a command to invoke that capability.

The intent layer is a planning aid. It never grants permissions, bypasses owner settings, or executes tools. Existing dispatch authorization, capability-pack checks, Android permissions, approvals, cancellation, executor ownership, and evidence recording remain authoritative.

## Execution pipeline

```text
User message
   |
   v
Conversation resolver
   |-- recent user/assistant turns
   |-- active task objective
   |-- durable in-flight task checkpoint
   |-- references: it / that / continue / remaining work
   v
Intent frame
   |-- new objective vs follow-up vs correction vs test request
   |-- resolved objective and reference source
   |-- ambiguity / clarification requirement
   v
Goal understanding + live capability discovery
   |
   v
Planner (model interprets the full request and context)
   |
   v
Readiness + owner policy + safety preflight
   |
   v
Assigned executor -> actual operation -> evidence ledger
   |
   v
Completion reconciler -> truthful final response
```

## Open-source design references

### Hermes Agent

Repository: https://github.com/NousResearch/hermes-agent

Useful architectural patterns studied:
- A distinct conversation loop with named phases for preparation, provider calls, tool rounds, errors, truncation, and recovery.
- Prompt construction separated from tool execution and context management.
- Persistent session search in addition to bounded curated memory, so the agent can retrieve an earlier task instead of assuming a short transcript contains everything.
- Interrupt-and-redirect behavior that treats a correction as a new instruction to the current task rather than an unrelated keyword trigger.

Orion adaptation:
- Keep intent resolution, goal framing, planning, execution, and completion evidence as separate stages.
- Use bounded transcript context for immediate references and durable task checkpoints for interrupted work.
- Do not copy Hermes' server/CLI runtime into the Android app; reuse the architectural separation, not its infrastructure assumptions.

### nanoMuse

Repository: https://github.com/Kingijimzy/nanoMuse

Useful architectural patterns studied:
- A local agent on each device, with device-specific capabilities and task execution.
- A task/approval boundary that is separate from the model's tool selection.
- Explicit task progress and recovery semantics.
- Privacy-aware device-local data handling and a separate policy gate (Sentinel) between the model and execution.

Orion adaptation:
- Keep the user's phone as the authority for local device actions and approvals.
- Treat the intent interpreter as untrusted planning input; it cannot override the existing owner control center or Android security.
- Reconcile task progress from durable local checkpoints before continuing after process death.
- Do not copy GPL-3.0-or-later nanoMuse source into PhoneAI unless the project explicitly chooses the resulting licensing obligations. This implementation uses independently written Kotlin and adopts design ideas only.

## Current implementation scope

The first implementation adds:
- A bounded conversational reference resolver for continue/resume, finish remaining work, correction of a prior task, and generic test/verify follow-ups.
- Resolution against the active task objective, recent substantive user turns, and the durable task checkpoint command.
- An explicit ambiguity result that stops execution and asks one focused question instead of inventing a target.
- A context contract injected into both Talk-mode routing and the Command planner.
- Regression tests for follow-up resolution, misleading capability words, current-turn transcript duplication, and unresolved references.

This is an initial foundation, not a claim of complete semantic understanding. The model planner still interprets complex natural language, and future work should add durable session retrieval, structured multi-objective tracking, and a completion reconciler that checks every requested outcome against evidence.


## Multi-objective graph foundation (2026-10-30)

The current implementation adds `OrionObjectiveGraph`:
- Accepts a bounded structured objective list (1–24 nodes) with stable IDs, titles, explicit dependency IDs, status and short evidence.
- Rejects malformed/duplicate IDs, missing dependencies, self-dependencies, duplicate edges and cycles.
- Computes a dependency-first topological order and identifies objectives whose prerequisites are complete.
- Prevents a dependent objective from being marked complete before its prerequisites.
- Persists the active graph in app-private SharedPreferences and restores it after process restart.
- The planner can return an `objective_graph` object; Agent validates it before saving. Invalid proposals are ignored and surfaced as a task warning.
- A new objective creates a durable root objective; follow-up context can resolve against that graph after the in-memory task and execution checkpoint are absent.

This is the graph/state foundation, not yet a complete execution ledger. Tool-to-objective attribution and automatic status transitions from each executor's verified evidence still need to be connected before Orion can enforce dependency gates during every action.

## Reference update

The current Hermes architecture documentation confirms a distinct agent loop, modular prompt/context construction, tool registry/backends, and SQLite + FTS5 session storage with session lineage. The durable-history lesson is to retrieve relevant earlier sessions instead of relying only on a small recent-turn window. See https://github.com/NousResearch/hermes-agent/blob/main/website/docs/developer-guide/architecture.md.

The nanoMuse Android/architecture documentation illustrates per-thread agents sharing durable memory/goals and atomic, versioned conversation persistence. This is a useful pattern for Orion's local-first Android runtime; retain Android-side approval and device ownership. See https://github.com/nano-muse/nanoMuse/blob/main/docs/android.md and https://git.mitgai.net/nano-muse/nanoMuse/blob/main/docs/architecture.md.

The next required layer is a bounded local conversation index (session ID, turn ID, role, timestamp, text digest/full text under retention limits), ranked retrieval for references such as "the earlier keyboard crash" or "the task we paused last week", and an evidence reconciler that updates graph node states only from verified executor outcomes. Retrieval must return source turns and dates so the planner can distinguish remembered user requirements from assistant claims.


## Durable conversation index and executor evidence (2026-10-30)

The repository already had a SQLite-backed `OrionConversationRepository`; this iteration extends that existing source of truth rather than introducing a parallel transcript database.

### Conversation index
- Schema version 3 adds an on-device FTS4 virtual table, maintained by insert/update/delete triggers.
- Existing conversations are indexed during the schema migration; both active and archived sessions are searchable.
- Search results return the stable `sessionId` and `turnId`, role, timestamp, title, a bounded excerpt and adjacent-turn context.
- Message IDs are UUIDs for new rows. During a transcript save, unchanged messages at the same turn position retain their IDs; changed/new messages receive fresh IDs.
- The legacy SharedPreferences archive import remains one-time and the SQLite store remains canonical.
- Search runs locally without model calls. The agent adds retrieved excerpts to contextual follow-up prompts and labels them as historical data, not instructions. Assistant statements are never treated as proof of action success.

### Objective-to-executor evidence
- Schema version 3 adds `objective_evidence`, linked by foreign key to the originating conversation session and (when available) the latest user turn ID.
- Each receipt stores objective ID, actual tool name, outcome, bounded/redacted executor result summary and timestamp.
- Every model-planned tool dispatch is checked against the persisted objective graph at the real `executeWithRecoveryGuard` boundary. Multi-objective graphs require an explicit valid `objective_id`; objectives with incomplete prerequisites are blocked before dispatch.
- Actual tool results are recorded against the selected objective and the receipt ID is returned to the planner.
- A completion proposal must reference an exact successful receipt already stored for that objective. The runtime re-checks prerequisite completion before changing the objective state.
- Planner-provided status/evidence fields are ignored. Replanning preserves already-running/blocked/completed runtime state; a provisional single-node `main` objective is replaced when the planner expands it into named objectives.
- Failed results leave the objective blocked and eligible for a changed approach; process interruption leaves the durable checkpoint and objective state available for reconciliation.

### Remaining validation
- Run the new Robolectric SQLite migration, FTS search, stable-turn-ID, and evidence-link tests in CI.
- Verify database migration on an existing install with archived chats, not only a fresh database.
- Physical-device verification is still required on the Redmi device.
- The completion gate currently proves an exact successful executor receipt and dependency completion. Domain-specific objective validators (for example, checking that a saved file exists or a message is actually delivered) should be added for consequential outcome types before claiming those objectives complete.


### Vague-reference recovery

When a follow-up contains no useful search terms (for example, "fix that" or "continue what we discussed last time"), the agent falls back to the most recently updated durable session and retrieves its last bounded turns. It re-runs contextual resolution against those source turns, excluding follow-up-only messages from becoming the objective. If the historical session still does not identify a substantive user objective, Orion asks for clarification rather than inventing one.


Objective graphs are now keyed by the active conversation session ID. Switching between archived chats restores that session's own objective graph; clearing a chat removes its graph instead of leaking another thread's active task. Completion additionally requires that the evidence ID is the graph node's latest recorded receipt and belongs to the currently active session.
