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
