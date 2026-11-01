# Orion Background Specialist Execution — Architecture Decision Record

**Status:** Proposed implementation contract; not a claim that background specialist execution is implemented.
**Date:** 2026-10-31
**Target branch:** `orion/autonomous-companion-roadmap-20261031`
**Parent work:** PR #145 (stacked on PR #132)

## Decision

Implement one bounded, durable specialist execution path owned by the central Orion brain. The existing `OrionWorkerJobStore` remains the durable job ledger and source of truth for specialist-job state. AndroidX WorkManager is the scheduler/recovery mechanism; it is not the job database and it is not proof that a job is running. The worker invokes the existing specialist/provider logic through a narrowly scoped execution adapter and returns a durable receipt to the originating conversation.

Do not introduce a second task coordinator, a second specialist registry, a second transcript store, or a second memory database.

## Existing resources to reuse

The current app already includes:

- AndroidX WorkManager KTX `2.9.1` in `app/build.gradle.kts`.
- OkHttp `4.12.0` for network transport.
- `OrionWorkerJobStore` for bounded, idempotent, AtomicFile-backed child-job state.
- `OrionAgentDelegation.Manifest` and `CapabilityBroker` for least-privilege specialist scope.
- `AgentRunner.runFull` for existing specialist prompts and provider routing.
- `AgentCheckpointStore` for the exact parent task/checkpoint lifecycle.
- `OrionConversationRepository` and the existing SQLite chat-event stream for durable conversation events.
- Existing request-budget and provider-routing policy; do not silently create an independent unlimited worker budget.

No new library is justified for the first implementation slice. Keep the existing dependency versions until a concrete missing API is demonstrated. In particular, do not add a second HTTP client, coroutine framework, database, or agent framework just to wrap the existing runtime.

## Runtime contract

### Enqueue

1. The central Agent creates a specialist manifest with an exact parent checkpoint ID, parent objective ID, specialist ID, allowed read-only tools, memory scope, model-call budget, capability-call budget, and deadline.
2. Persist the manifest/job relationship before scheduling work. The persisted record must be sufficient to reconstruct the same scoped assignment after process death; never rely on an Activity, Agent instance, callback, or in-memory transcript.
3. Use a deterministic WorkManager unique-work name derived from the durable job UUID, e.g. `orion-specialist:<job-id>`, and `ExistingWorkPolicy.KEEP`. A duplicate enqueue must resolve to the original job and must not schedule a second provider charge.
4. Pass only the durable job ID to WorkManager input data. Do not put prompts, credentials, API keys, conversation text, images, or private account data in WorkManager input or tags.
5. Constrain provider-backed work to a connected network. Use bounded backoff only for failures known to have happened before a provider request was dispatched.

### Execute

1. A single worker claims the job through `OrionWorkerJobStore`; the ledger's one-active-job limit remains authoritative even if WorkManager starts multiple workers.
2. Re-read and validate the exact parent checkpoint immediately before provider work. Missing, replaced, paused, stopped, archived, or otherwise unverifiable parents fail closed.
3. Reconstruct the persisted specialist manifest and intersect its allowed tools with the live `ToolRegistry`. Every capability call must use the parent's existing guarded dispatch boundary; specialist code never calls an Android action, shell, messaging, account mutation, or exchange-order API directly.
4. Enforce a single shared provider-request budget, maximum model calls, capability-call budget, prompt/output bounds, and a hard deadline. Provider fallback must not replay a request after partial output or an uncertain completion.
5. Check cancellation before provider dispatch, during transport where supported, after the provider returns, before persisting the result, and before delivering it to chat.
6. Only the worker that still owns the matching RUNNING job lease may write a terminal receipt. Late or duplicate results are discarded.

### Completion and conversation delivery

1. Persist the sanitized result/evidence receipt in the job ledger first.
2. Append a durable event to the existing originating conversation using its stable conversation/session identifier. Event types should distinguish `specialist/queued`, `specialist/started`, `specialist/completed`, `specialist/failed`, `specialist/cancelled`, and `specialist/needs_review`.
3. Events must carry stable job/task IDs, timestamps, a short safe summary, and a result reference/receipt—not credentials or raw provider payloads.
4. The open chat may be notified immediately, but it must also be able to restore the same event after Activity recreation or process restart.
5. The parent remains `waiting_for_children` until every required child has a durable terminal receipt. A child result is evidence for the parent brain to review, not permission to execute a consequential action.

## Cancellation, interruption and retries

- Parent pause/stop/archive/replacement cancels queued and running children and cancels their unique WorkManager work.
- Worker cancellation must reach the underlying HTTP call where the transport supports cancellation. A coroutine cancellation flag alone is not enough if a blocking request continues in the background.
- If the process dies after the provider may have accepted a request, do not automatically replay it. Move the job to `NEEDS_REVIEW` unless an idempotent provider request ID proves it is safe to resume.
- Retry only known pre-dispatch failures or explicitly idempotent reads. Rate-limit responses may be retried only under the shared budget and bounded backoff; never retry an ambiguous completion.
- Keep the one-active-job limit on the phone. WorkManager's scheduler concurrency is not a substitute for the ledger claim.
- Android/HyperOS may defer or kill background work. Report queued, running, deferred, interrupted and completed states from actual WorkManager + ledger evidence. Never promise always-on execution.

## Safety boundary

Specialist background jobs are limited to analysis, read-only research, trading analysis and task planning. They cannot perform phone actions, send messages, publish content, mutate accounts, execute shell commands, or place real-money orders. A trading specialist may analyze a setup and return a proposal; the central chat must obtain the normal explicit approval and use the existing risk/confirmation gate for any later order flow. No worker may bypass the central Agent, ToolGate, AgentPolicy, pack settings, OS permissions, safety preflight, evidence checks or owner confirmation.

## Low-resource policy

The target Redmi A3 Pro has 4 GB RAM. Keep one local worker active, use provider APIs rather than a bundled local LLM, avoid persistent foreground services for ordinary research, avoid polling, cap input/output and retained receipts, and schedule only bounded work. Use a foreground service only for a user-visible operation that genuinely qualifies under Android policy and has an explicit user-facing notification.

## Required implementation and verification gates

- [ ] Persist enough manifest/conversation metadata to reconstruct a job after process death without exposing secrets.
- [ ] Add a WorkManager worker and unique-work scheduler around the existing ledger; do not create a second job store.
- [ ] Add provider request reservation/deadline/cancellation support shared with foreground requests.
- [ ] Make the underlying HTTP request cancellable; prove cancellation stops or closes transport, not merely that late output is ignored.
- [ ] Add idempotent result persistence and durable originating-chat event delivery.
- [ ] Add tests for duplicate enqueue, duplicate WorkManager scheduling, two simultaneous workers, parent pause/stop/archive, process death before dispatch, process death after dispatch, provider timeout, cancellation during HTTP, budget exhaustion, stale worker completion, and duplicate event delivery.
- [ ] Add an end-to-end test: central chat → specialist enqueue → WorkManager execution → provider result → durable receipt → original chat event → app restart → same receipt restored.
- [ ] Run exact-head CI (unit tests, restricted Python tests, debug APK build, APK package/signature validation and artifact upload).
- [ ] Run physical Redmi A3 Pro acceptance for background restrictions, notification behavior, process death/recovery, battery use and chat continuity.

## Explicit non-goals for this slice

- Always-on microphone or unrestricted background service.
- Parallel local LLM inference on the phone.
- Autonomous real-money trading or any background exchange order.
- Replacing the existing central task/checkpoint architecture.
- Treating a scheduled WorkManager request as proof of provider execution.
- Marking the roadmap stage complete based only on compilation or unit tests.

## Completion rule

Stage 3 is not complete until the full end-to-end test passes and the physical-device limitations are documented. A green APK build alone proves packaging, not independent background execution.
