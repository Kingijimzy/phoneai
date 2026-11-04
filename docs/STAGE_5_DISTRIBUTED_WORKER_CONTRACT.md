# Stage 5 — Distributed Worker Execution Contract

Status: implementation specification; not a completion claim.

## Objective
Extend the on-device Central Brain into a durable coordinator for remote or local specialist workers. The phone remains the owner-controlled execution endpoint. Workers may plan and perform explicitly granted remote work, but they never inherit Android permissions or owner authority.

## Non-negotiable invariants
1. One canonical task ID and parent task ID across chat, voice, MCP, scheduled work, and delegated workers.
2. Every state transition is durable before it is announced to the UI or parent worker.
3. Worker input is a typed, minimized task envelope, never an unrestricted conversation dump.
4. Worker output is untrusted until schema validation, policy evaluation, evidence attachment, and parent verification complete.
5. At-least-once delivery is assumed. Side effects require idempotency keys and an execution ledger; ambiguous outcomes are never replayed automatically.
6. Cancellation, expiry, owner revocation, and parent termination propagate to descendants.
7. Remote workers cannot invoke Android, accessibility, shell, private workspace, trading execution, or owner-only capabilities directly. They return proposals to the phone's guarded dispatcher.
8. Model output cannot alter policy, grant permissions, register executable code, or mark its own work verified.

## Canonical task envelope
Required fields: schema_version; task_id, parent_task_id, root_task_id; objective, task_kind, requested_capabilities; input_refs; owner_scope; created_at, deadline_at, attempt, idempotency_key; trace_id, span_id, cancellation_token; result_schema; policy_version.
Reject unknown schema versions, oversized payloads, expired deadlines, missing parent/root relationships, unregistered capability names, and malformed references.

## State machine
CREATED -> QUEUED -> LEASED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED | EXPIRED | NEEDS_OWNER
- A worker lease has an owner, expiry, heartbeat timestamp, and monotonically increasing fencing token.
- Lease renewal compares the fencing token. A stale worker cannot commit after a newer lease is issued.
- A retry creates a new attempt under the same task ID and idempotency key.
- Terminal states are immutable except for append-only correction/verification events.
- Parent completion requires every required child to be terminal and every required result to pass parent verification.

## Persistence and recovery
Use a durable relational store for task rows, append-only task events, worker leases, idempotency records, and checkpoint snapshots. Use transactional writes for state + event + outbox records. Dispatch from an outbox so a crash between commit and queue publication cannot lose work.
On restart: expire stale leases; reconcile outbox records; resume only retry-safe tasks; mark uncertain side effects NEEDS_OWNER; rebuild parent progress from durable child events rather than in-memory counters.

## Worker contract
Workers expose typed accept, heartbeat, checkpoint, complete, fail, and cancel operations. Every call authenticates worker identity, validates lease/fencing token, enforces payload limits, and writes an audit event.
Workers receive only minimum task data and approved memory categories. Secrets are scoped references resolved only by an authorized connector, never copied into prompts or logs.

## Evidence and verification
Each result includes structured output matching result_schema; evidence references with source, timestamp, digest and access scope; actions attempted and idempotency keys; warnings, omissions and uncertainty.
The coordinator validates evidence references, checks capability registration, and independently verifies deterministic outcomes. A worker cannot self-approve a repair or external side effect.

## Cancellation and owner controls
Cancellation is durable and recursive. Workers check it before each new side effect and before committing a result. Owner revocation invalidates grants and active leases. Pause/resume is a coordinator state transition, not a prompt instruction.

## Observability
Propagate W3C trace context across coordinator, queue, worker, provider and phone callback. Record lifecycle events, lease changes, retries, latency, token/cost usage, policy decisions and verification outcomes. Redact credentials, private content and sensitive tool arguments.

## Delivery sequence
1. Define shared Kotlin/JSON schema and strict validators.
2. Add local durable task/event/outbox repositories and migration tests.
3. Add lease + fencing + idempotency state machine.
4. Add worker transport abstraction and deterministic in-process worker.
5. Add remote worker adapter and authenticated transport.
6. Add LangGraph adapter behind the same typed contract; LangGraph is orchestration, not authorization.
7. Add recovery, cancellation propagation, parent/child aggregation and trace propagation.
8. Add capability-limited research, coding, diagnostics and trading-analysis workers.
9. Add restart/replay tests and operational metrics.
10. Keep remote execution disabled by default until owner enables a specific worker and scope.

## Stage exit criteria
Stage 5 is complete only when the same task can be created from chat and voice, delegated to a worker, checkpointed, interrupted, resumed after coordinator restart, cancelled recursively, and returned with verifiable evidence—without duplicate side effects or permission escalation. Unit tests alone do not certify production readiness; device and hosted-worker acceptance remain separate gates.