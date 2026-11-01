# Orion Autonomous Companion — Implementation Roadmap
**Date:** 2026-10-31  
**Implementation branch:** `orion/autonomous-companion-roadmap-20261031`  
**Starting integration commit:** `45c37779b57f7b57e681ed50e94458121b337499`  
**Upstream integration:** draft PR #132, based on the Python executor stack. PRs #140 and #144 are merged into that integration branch, not into `main`.

## Product objective

Orion is a persistent, interactive personal AI companion—not a chat UI that occasionally calls tools. One central brain owns intent understanding, planning, delegation, execution coordination, memory, recovery and user-facing explanations. It can make progress without the user continuously prompting it, within explicit capability grants and Android's actual operating-system limits.

The phone remains the trusted device endpoint. Expensive research, parallel agents and long-running work may use a remote runtime. Both sides share task IDs, state, capability grants and evidence. Provider changes must not change Orion's identity or erase task continuity.

## Non-negotiable engineering rules

1. **One task lifecycle:** new objective → plan → authorize → execute → verify → persist → report. Talk, Command, voice, specialist agents, shortcuts and background work must converge on the same lifecycle or use a documented adapter into it.
2. **One capability truth source:** discover registered tools and their real prerequisites at runtime. Registration is not proof of live health. Never claim an unavailable action is available or invent a permission blocker.
3. **No unnecessary consent:** use already-granted, enabled, pre-authorized capabilities directly. Ask once when a real Android permission, account setup, owner policy or consequential-action rule requires it. Do not ask again for an action already authorized within its saved scope.
4. **Deterministic safety boundary:** model reasoning may propose an action; policy code decides whether it can run. External publication, sensitive communication, destructive changes, security-sensitive operations and real-money trading require the appropriate approval.
5. **Evidence before success:** a tool response is not automatically proof that the user's goal is complete. Verify observable outcomes and preserve bounded evidence.
6. **Durable work:** persist task identity, plan, checkpoint, cancellation, retry state and outcome. Resume without replaying completed steps. Never blindly retry an uncertain write.
7. **Provider-neutral identity:** memory, owner preferences, active objectives, agent registry and receipts live outside provider-specific conversation context.
8. **Safe self-improvement:** research and draft new skills in isolation; test them; record provenance, license, version and permissions; promote only under the configured owner policy. Never let generated skills rewrite privileged safeguards.
9. **Honest background behaviour:** WorkManager and remote workers can provide durable scheduling, but Android/HyperOS can defer or stop work. Report actual scheduler/service state and never promise unrestricted always-on execution.
10. **Device-first resource discipline:** keep the Redmi A3 Pro client lightweight; use remote workers for expensive parallel reasoning. Bound model calls, context, retries, concurrency, memory and notification frequency.

## Verified starting point and known integration debt

- The active integration head is `45c37779b57f7b57e681ed50e94458121b337499`; it is the head of draft PR #132.
- PR #140 and PR #144 are merged into that integration branch. Their reported scope includes tool-loop/repetition safeguards, bounded output handling, Claude's Trading Desk/chain-monitoring work and acceptance-test fixes.
- PR #132 remains open/draft and is stacked on `orion/extract-python-extensions-executor`.
- `main` is currently at `083a6bba4261fa75abd0339d1d393b313f05c59c`, behind the integration stack. Do not treat the integration work as released on main.
- The integration audit reports 137 registered tools with 137 adapter routes and no structurally orphaned registered tools. That is a source ownership check, not proof every integration works on the physical phone.
- The same audit identifies remaining splits: the primary Agent still owns much of task control flow; live voice is conversation-only; specialist agents use a separate model path; background work has a separate queue; deterministic research shortcuts do not yet complete the full checkpoint/objective receipt lifecycle.
- The central task context currently injects too many full tool contracts. Capability selection must become request-scoped rather than sending all contracts on every turn.
- The Trading Desk is present in the integration stack. Keep read-only market analysis, strategy research, backtesting and paper trading available through the shared brain. Real exchange orders must remain separately risk-checked and explicitly confirmed.
- Physical Redmi verification remains a separate release gate. CI cannot prove HyperOS background scheduling, voice capture/playback, accessibility, keyboard switching, notifications or thermal stability.

## Roadmap and completion gates

### Stage 0 — Establish a trustworthy integration baseline
**Purpose:** ensure we are upgrading the real latest source, not a stale branch.

Tasks:
- [ ] Reconcile the Python-executor base, PR #132 and merged PRs #140/#144; document exact ancestry and changed-file overlap.
- [ ] Verify PR #132 exact-head CI, including Kotlin tests, restricted Python tests, APK build, package/signature checks and artifacts.
- [ ] Review PR #132 for regressions and unresolved review threads; resolve or document every blocker.
- [ ] Promote the validated integration stack through a reviewable PR to `main`; do not force-push or merge a failing/draft stack.
- [ ] Record the exact promoted commit and a reproducible baseline test report.

**Exit gate:** exact-head CI passes; integration PR is mergeable; no unresolved critical review issue; Trading Desk routes and policy tests are present; main contains the validated integration commit.

### Stage 1 — One central brain and one execution lifecycle
**Purpose:** remove the chatbot/tool-path fragmentation.

Tasks:
- [ ] Make a single durable objective/task coordinator the owner of planning, step state, checkpoint transitions, verification and completion.
- [ ] Route Talk, Command and deterministic fast intents into that coordinator. Talk should not require an unnecessary extra model classification call for obvious actionable intents.
- [ ] Give live voice a tool-enabled execution surface using the same coordinator, capability registry, owner policy, cancellation and evidence path.
- [ ] Adapt specialist agents to receive scoped task envelopes and call the shared capability broker rather than using a disconnected model-only execution path.
- [ ] Adapt background tasks and standing orders to the same objective IDs, checkpoint format and evidence receipts.
- [ ] Close the deterministic research shortcut lifecycle gap: checkpoint step, objective receipt, cancellation and completion reconciliation.
- [ ] Preserve task IDs and checkpoint state across app restart, provider switch, chat archive and mode changes.
- [ ] Add idempotency keys and uncertain-write recovery; never replay a possibly applied write without reconciliation.
- [ ] Keep direct tool execution impossible outside the shared policy/dispatch boundary.

**Exit gate:** all entry surfaces produce the same durable task record and evidence format; interruption/resume tests prove no duplicate completed actions; voice can execute only through the same gates.

### Stage 2 — Capability intelligence and fast routing
**Purpose:** Orion knows what it can actually do and uses it without needless friction.

Tasks:
- [ ] Build request-scoped capability retrieval: intent → relevant capability families → exact tool contracts only.
- [ ] Keep capability health separate from registration, setup state, OS permission, owner grant and live execution proof.
- [ ] Use deterministic routing for clear local intents and a bounded planner for ambiguous or compound goals.
- [ ] Remove redundant model calls, repeated full-registry prompt injection and duplicate approval prompts.
- [ ] Add provider budget policy: primary provider first; fallback only on defined transient/availability errors; cap retries and stop repeated empty responses.
- [ ] Ensure provider/model catalogues are fetched and cached; never force unverified model IDs.
- [ ] Add performance telemetry for time-to-first-response, tool selection accuracy, model-call count, retries, token/context size and completion rate.

**Exit gate:** benchmark suite demonstrates lower latency and fewer model calls than baseline without reducing task success or policy coverage.

### Stage 3 — Specialist agent factory and delegation
**Purpose:** Orion can create and manage purpose-built workers.

Tasks:
- [ ] Define an agent manifest: identity, objective, skills, allowed capability packs/tools, memory scope, budget, deadline and reporting contract.
- [ ] Add dynamic agent creation from reviewed templates (researcher, coder, trading analyst, social-media planner, phone operator, learning/review agent).
- [ ] Add delegation envelopes, task IDs, deadlines, cancellation, progress events and structured results.
- [ ] Enforce least-privilege inheritance: subagents cannot grant themselves tools, expand parent permissions or access unrelated secrets.
- [ ] Add parallel execution only for independent work; central brain reviews and consolidates outputs.
- [ ] Persist agent registry and agent task history across provider changes and app restarts.
- [ ] Add agent lifecycle UI: create, inspect, pause, resume, stop, revoke, archive and review.

**Exit gate:** multi-agent research/coding workflow completes with traceable delegation, bounded resources, isolated permissions and a verified consolidated report.

### Stage 4 — Persistent memory, learning and self-evolution
**Purpose:** Orion improves from outcomes under owner supervision.

Tasks:
- [ ] Define canonical memory layers: identity/preferences, facts, decisions, lessons, skills, active objectives, agent state and evidence.
- [x] Add provenance, confidence, timestamps, sensitivity labels, and correction/deletion semantics to local memory records.
- [ ] Add retention/expiry rules and a complete user-visible provenance/audit history.
- [ ] Make memory provider-neutral and accessible to every authorized agent through filtered views.
- [x] Learn from completed task receipts, user corrections, clean successful workflows and verified failures; do not learn blindly from model guesses or untrusted tool output.
- [x] Keep uncertain/model-originated memories pending owner review; sensitive memories are excluded from model context even if approved.
- [ ] Build a skill factory: discover gap → research sources → inspect license → draft → sandbox tests → permission review → version → owner-approved activation → rollback.
- [x] Keep privileged security/policy code outside generated-skill write access; current skill registration is declarative and cannot execute arbitrary code.
- [ ] Add periodic memory consolidation with bounded cost and a user-visible audit trail.

**Exit gate:** a lesson learned in one provider/agent is applied correctly by another; a new skill passes tests and remains inactive until authorized; rollback is verified.

#### Stage 4 implementation log — owner-reviewed memory provenance

- Extended local memory records with source role, confidence, sensitivity, review status and bounded evidence metadata. Existing records migrate as legacy, lower-confidence, pending-review entries rather than being silently trusted.
- Model/tool-originated memory writes and edits are pending review. Direct entries and edits made in the Memory screen are owner-confirmed.
- Added approve/reject controls and visible provenance/review labels to the Memory screen.
- Only owner-approved, non-sensitive memories are injected into the central Agent's trusted long-term-memory prompt block. Likely credentials, seed phrases, private keys, OTPs and similar content are labelled sensitive and excluded even after approval.
- Preserved the new metadata in the undo journal so reversing a deletion does not silently erase its provenance or trust state.
- Added deterministic policy tests for review gating, sensitive-memory exclusion and confidence bounds.

**Remaining Stage 4 work:** migrate the other memory layers to one canonical schema; add retention/expiry and audit history; expose filtered memory views to all authorized specialists; complete license-aware skill review and sandbox testing; add bounded consolidation and cross-provider learning acceptance tests.

### Stage 5 — Proactive watcher and background work
**Purpose:** Orion notices relevant events and makes useful progress without being prompted.

Tasks:
- [ ] Define event sources and subscriptions: authorized market feeds, calendar/session events, task deadlines, device state, selected news/topics, service status and user-defined triggers.
- [ ] Implement event intake → normalize → filter → deduplicate → prioritize → brain investigation → optional delegation → notification.
- [ ] Store watcher rules, last-seen cursors, dedupe keys, quiet hours, rate limits and delivery receipts.
- [ ] Use WorkManager for durable deferred/periodic Android work; use foreground services only for valid user-visible ongoing operations; use remote workers for long-running/parallel research.
- [ ] Respect notification permission, background-start restrictions, battery optimization and Xiaomi autostart constraints.
- [ ] Notify only when useful: what happened, why it matters, what Orion checked/did, evidence and whether a decision is needed.
- [ ] Add pause-all, per-source controls, quiet hours and emergency stop.
- [ ] Never initiate real-money trades or publish/send externally from a watcher without the required explicit grant and approval.

**Exit gate:** simulated and real authorized events trigger one deduplicated, evidence-backed notification; restart and network interruption do not lose queued work; OS limitations are reported accurately.

### Stage 6 — Unified companion experience
**Purpose:** make every feature feel like one coherent Jarvis-like system.

Tasks:
- [ ] Make chat the central hub for asking, planning, supervising agents and reviewing work.
- [ ] Keep Talk/Assist/Autopilot/Builder modes consistent with the real policy map; mode changes must not silently weaken explicit per-tool settings.
- [ ] Add visible task timeline, live phase, agent activity, approval request, evidence, pause/resume and recovery actions.
- [ ] Connect voice, chat, Trading Desk, browser, phone control, memory and task centre to the same identity and task system.
- [ ] Ensure voice uses the same tools and permissions as chat; distinguish listening, thinking, acting and speaking.
- [ ] Keep the black/graphite/silver UI direction; avoid excessive home buttons, oversized translucent cards and unnecessary accent colours.
- [ ] Improve accessibility, low-memory behaviour, reduced-motion support and graceful offline fallback.

**Exit gate:** a user can start a task in chat, continue by voice, inspect a delegated agent, pause it, resume after restart and review its result without losing context.

### Stage 7 — Verification, device acceptance and release
**Purpose:** prove the companion works on the actual device.

Tasks:
- [ ] Run exact-head CI for every integration commit.
- [ ] Add contract tests for every registered tool route and executor owner.
- [ ] Add end-to-end tests for task creation, compound planning, approval, cancellation, resume, provider fallback, agent delegation, memory sharing, proactive events and Trading Desk.
- [ ] Verify no duplicate dispatch, no false success, no unauthorized action and no secrets in prompts/logs/backups.
- [ ] Execute the Redmi A3 Pro / HyperOS matrix: install/update, first launch, chat, keyboard, accessibility, browser, notification access, voice, provider failure, background recovery, restart, battery saver, heat and memory pressure.
- [ ] Capture APK SHA, CI run, device build/OS, test evidence and known limitations for each release.
- [ ] Keep blocked physical tests marked BLOCKED—not PASS—until run on the device.

**Exit gate:** exact-head CI passes and the device matrix has evidence for every required scenario; known limitations are documented.

## First implementation sequence

1. Complete Stage 0 ancestry/CI/review reconciliation.
2. Close the single-lifecycle gaps already identified by the source audit.
3. Introduce request-scoped capability selection to reduce prompt size and latency.
4. Unify specialist agents and voice with the central broker.
5. Add durable agent manifests and least-privilege delegation.
6. Consolidate memory and task receipts.
7. Implement proactive event intake and Android/remote scheduling.
8. Finish companion UI, then execute physical acceptance.

## Release acceptance scenarios

1. A broad objective is understood, planned and executed without the user naming tools.
2. Orion researches a topic, delegates parallel subquestions and consolidates evidence.
3. Orion creates a specialist agent with only the capabilities required for its task.
4. A task survives process death and resumes without repeating completed writes.
5. A provider switch preserves identity, memory, active objectives and agent state.
6. A registered but unconfigured tool is reported as unavailable for the exact reason; Orion tries a valid alternative.
7. A pre-authorized reversible task runs without repeated consent.
8. A sensitive external action asks for approval once and records the decision.
9. A real-money trade cannot execute without its separate risk and per-order confirmation path.
10. A new capability gap produces a sourced, licensed, tested skill proposal—not a false claim that a skill was installed.
11. An authorized event produces a deduplicated, useful notification.
12. A failed or uncertain action is reported honestly and is not blindly repeated.
13. Voice can plan and execute through the same tool policy as chat.
14. The Trading Desk remains reachable through the central brain and retains its research, backtest and paper-trading capabilities.

## Progress ledger

| Stage | Status | Evidence / blocker |
|---|---|---|
| Stage 0 — integration baseline | IN PROGRESS | PR #132 draft; upstream stack is not yet on main; exact-head CI/review must be checked |
| Stage 1 — central lifecycle | IN PROGRESS — voice and deterministic research receipts added | actionable live voice uses the shared executor; weather, news, sports, web-search, freshness, market-quote, GitHub account, repository-name search and Actions run/list shortcuts persist receipts; full repository source inspection remains direct guarded research; parent-child cancellation/recovery and background continuity remain |
| Stage 2 — capability intelligence | IN PROGRESS — request-scoped contracts added | relevant capability families receive full contracts while all other tools remain discoverable by compact signatures; exact-head CI and latency/token comparison are pending |
| Stage 3 — specialist delegation | IN PROGRESS — scoped manifests, receipts and durable child-job ledger added | specialist calls still execute synchronously; the new bounded job store adds idempotent enqueue, one-at-a-time claims, exact-parent fail-closed checks, parent cancellation and interrupted-call NEEDS_REVIEW recovery; provider-backed background execution and UI controls remain |
| Stage 4 — memory/self-evolution | PARTIAL | memory and extension lab exist; canonical cross-agent memory/learning needs integration proof |
| Stage 5 — proactive background | PARTIAL | watcher/runtime/standing-order foundations exist; event pipeline and device delivery need proof |
| Stage 6 — companion experience | IN PROGRESS — central chat awareness and deterministic entry routing added | Orion Chat now receives the compact/detailed runtime app map and sends explicit repository, terminal/runtime and website tasks into the guarded Agent path; broader screen/workflow ownership and task/voice continuity still need implementation and device proof |
| Stage 7 — verification | BLOCKED | CI can be inspected remotely; physical Redmi acceptance has not been evidenced |

**Completion policy:** a stage is complete only when its exit gate is met and its evidence is recorded. Source presence, a green older CI run, or an untested APK is not sufficient.


## Implementation notes — PR #145

- Request-scoped tool contracts: `ToolRegistry.promptSection(request)` now supplies full descriptions for relevant request families and compact name/argument signatures for every other registered tool. The no-argument full snapshot remains available for diagnostics. Selection is only a prompt optimization; runtime authorization is unchanged.
- Voice execution bridge: `OrionVoiceExecutionPolicy` routes actionable and contextual follow-up utterances from live voice into `runInternal`, reusing the same task planner, checkpoint, capability registry, owner policy, confirmation and evidence gates as chat. Ordinary conversation remains on the low-cost voice conversation path.
- Regression tests cover prompt discoverability/selection and voice action routing/contextual follow-ups.
- PR #145 exact head is tracked in GitHub Actions. CI and Redmi device validation are not yet complete.


## Implementation notes — deterministic research lifecycle

- Added `OrionResearchShortcutLifecycle`, a bounded adapter that creates a durable task checkpoint before a direct research shortcut, persists each read-only step before/after dispatch, records errors, pauses safely on cancellation, requires recovery if evidence cannot be saved, and reports completion only when the completion marker persists.
- Routed weather, live news, live sports, explicit web search, freshness-dependent web lookup and public market quote shortcuts through that lifecycle. Each action still calls the existing guarded `research` dispatch path; this adapter does not grant permissions or bypass policy.
- Added `runDynamic` for bounded research sequences whose later inputs depend on earlier recorded outputs. Every dynamic step is checkpointed before dispatch and recorded before the next step is selected; individual read-only steps may explicitly tolerate a missing README/source file without hiding the failure.
- Migrated connected GitHub account identity + repository listing, public repository-name search, and exact Actions run/list reads into the same task lifecycle. Public repository inspection still uses the guarded read-only research path, but its multi-step metadata/README/tree/source sequence is not yet one checkpointed task; do not count it as lifecycle-complete.
- Metadata/tree failures stop the sequence; failed evidence writes require recovery; incomplete/cancelled work is not reported as complete. All GitHub calls still pass through the guarded Research/dispatch path.
- Added tests for successful receipts, existing-task begin failure, read errors, uncertain evidence writes, cancellation boundaries, failed completion persistence, dynamic dependent steps, tolerated read failures and hard-stop behavior.
- Remaining Stage 1 work is durable parent/child cancellation and recovery, plus continuity across specialist, voice and background work.


## Implementation notes — specialist delegation foundation

- Added `OrionAgentDelegation` manifests with a stable delegation ID, parent objective ID, sanitized objective, access class, allowed read-only data capabilities, memory scope and bounded model-call budget.
- Research specialists are limited to public read-only research inputs; trading coaches receive public ticker context only; planners and custom specialists default to analysis-only with no tool capabilities.
- Updated `Tools.askAgent` to persist a delegation start receipt before calling the specialist, inject the manifest into the specialist system context, and persist the result/failure receipt before returning it to the parent.
- A failed receipt write is surfaced as an error; the parent must not treat the delegation as completed.
- Specialist output remains advisory. The parent Agent retains all dispatch, policy, approval and external-action authority. This is not yet independent multi-agent tool execution, parallel delegation, or a complete agent lifecycle UI.
- Added tests for parent linkage, scope restrictions, custom-agent defaults, forbidden external actions, call budget and objective sanitization.


## Delegation hardening follow-up

- Delegated coach runs now receive a separate read-only market/strategy context; they do not fetch private account balances/positions through the normal coach context.
- Delegated specialist calls do not write new agent lessons or process coach action tags into local alerts/strategy proposals. Specialist output is returned to the parent for review.
- Added Robolectric persistence tests proving child delegation receipts retain their parent task ID and record completed/failed status.


## Delegation visibility and privacy hardening

- Specialist tasks are now linked to the active parent checkpoint when one exists. Their start, completion/failure, scope and bounded summary are stored in a capped local delegation ledger.
- The Task List response includes the five most recent specialist delegation receipts, so the parent brain can inspect what workers actually did.
- Delegated objectives are sanitized before they are passed to the specialist model. Empty responses and failed provider responses are recorded as failures, not successful delegations.
- Delegated specialists do not update agent lessons or process Trading Coach action tags. Delegated coaching receives public market context and saved strategy/journal context, not private account balances/positions or order controls.
- Research manifest capability names now match the actual read-only context sources supplied by the research specialist.


## Implementation notes — central chat as the companion hub

- `Agent.runConversation` now combines the normal deterministic action-intent policy with `ConversationAutoRoutePolicy`, so explicit public repository inspection, terminal/runtime checks, terminal code execution and website visits enter the guarded `runInternal` path without waiting for the conversational model to emit `ACTION`.
- `Agent.chatSystem` now injects `OrionAppAwareness.context(svc, query)`. Ordinary chat receives the compact runtime map; explicit architecture/capability questions receive the detailed map, including registered READ/WRITE tools, specialist profiles, skills, capability-pack settings, locally recorded service connection state, installed Android activities/services/receivers, local task/memory/knowledge counts and the execution gate.
- The awareness map is descriptive, not an authorization grant. The central Agent still dispatches through the existing guarded path; registered, configured, permission-granted, running and verified remain distinct states.
- Existing tests in `ConversationAutoRoutePolicyTest` cover terminal/runtime, code execution, public repository inspection, explicit website visits and ordinary-conversation non-routing. Exact-head CI for the new chat wiring is tracked by Actions run `41208677709`; physical Redmi verification remains pending.


## Implementation notes — parent brain, specialist delegation and companion continuity

- Explicit user requests to delegate/assign work to a named specialist now route from Talk into the guarded Agent execution loop. This includes task assignments to the coder, researcher, planner, writer, coach or a specialist/subagent. Ordinary mentions of agents do not trigger execution.
- The central Agent prompt now defines Orion as the parent orchestrator: decompose complex objectives into bounded subtasks, use `ask_agent` when a specialist materially helps, review specialist evidence, then continue the parent task itself through the existing guarded dispatcher and lifecycle. Specialists remain scoped workers; they do not inherit parent write permissions, phone-action authority, account mutation or trade execution.
- The main chat companion prompt now emphasizes conversational continuity, remembering the active objective, natural replies and useful initiative without claiming sentience, independent awareness or unobserved background activity.
- Added routing tests for explicit coder/researcher/planner delegation and negative coverage for casual agent mentions. Exact-head CI is required before treating these changes as verified.
- Remaining orchestration work: add a durable parent/child objective graph for multi-agent runs, show worker progress and returned evidence in the main chat timeline, allow cancellation/recovery of child work, and verify voice/chat handoff on a physical device.


- The parent brain and Talk chat now receive a compact, bounded specialist-work ledger (latest three receipts): worker identity, status, assigned objective, saved result summary and parent task link. This lets Orion maintain continuity across turns and review previous delegated work without relying on the model to remember transient output. Ledger entries are explicitly marked as evidence, not instructions.


## Implementation notes — specialist jobs belong to parent objective nodes

- Specialist delegation manifests and saved receipts now carry both the durable parent checkpoint ID and a separately validated parent objective-node ID. These IDs are no longer conflated.
- The Agent injects the canonical active objective ID into both ordinary and batched tool arguments before guarded dispatch. The task capability executor passes it into `Tools.askAgent`, and the specialist manifest persists it before the worker starts.
- Specialist prompt envelopes identify the parent objective node. The main chat's bounded worker ledger displays that objective association alongside the checkpoint ID, so Orion can tell which part of a multi-step goal each worker served.
- Added tests for valid/invalid objective association and persisted receipt continuity. This is linkage and observability, not permission inheritance: specialists remain read-only/analysis-only and the parent retains dispatch and approval authority.
- Exact-head CI is required for these latest changes. The next slice is to surface parent objective and specialist receipt events directly in the main chat timeline and make pause/cancel/recovery semantics explicit across the parent and child records.


## Implementation notes — specialist activity is visible in the main conversation

- The central Agent now emits a chat timeline event immediately before a single or batched `ask_agent` dispatch, identifying the worker, bounded assignment and parent objective ID where available.
- When the specialist call returns, Orion emits a separate RETURNED or FAILED event with a bounded result excerpt. A returned result is explicitly labelled for parent review; it is not described as completed parent work.
- These events use the existing chat note channel, so they appear in the same conversation rather than requiring the user to open Task Centre or a specialist screen. The saved specialist ledger remains the cross-turn continuity source.
- Delegation is currently synchronous within the parent Agent call. The visible assignment/return events do not imply independent background execution or live percentage progress. True asynchronous workers, cancellation propagation and durable child recovery remain future work and must not be represented as already implemented.
- Exact-head CI for the new timeline events is pending.


## Implementation notes — durable specialist events and live runtime awareness

- Specialist ASSIGNED, RETURNED and FAILED notes are now committed through the existing SQLite chat-event repository before the open chat is notified. They therefore survive activity recreation and process restart, follow the active conversation when it is archived/restored, and remain separate from user/assistant transcript turns.
- The central Agent's compact and detailed app-awareness context now includes the live runtime state, queued work count, bounded pending-work identifiers/checkpoints and last runtime event. This lets Orion answer from current runtime evidence instead of relying on conversational memory.
- The runtime status explicitly warns that Android/HyperOS can suspend or kill the process. WorkManager recovery is a best-effort wake-up path; it does not guarantee an always-running process or silently replay uncertain external effects.
- Exact-head CI and device verification are required before release. Remaining work includes durable parent/child cancellation and recovery semantics, surfacing runtime transitions as first-class timeline events, and testing service/process death and voice/chat continuity on the Redmi device.


- The service now writes durable `runtime/started`, `runtime/completed` and retry/failure events for meaningful queued work (standing-order checks, safe presence events, task outcomes and conversation restoration). The active chat can restore these events after reattachment.
- Each central Agent turn receives the latest bounded durable activity receipts alongside the live runtime snapshot. These are evidence summaries, not instructions or proof of work beyond the recorded status.

## Implementation notes — parent cancellation reaches specialist workers

- Added exact-parent-checkpoint cancellation inspection. A worker treats its parent as cancelled when that checkpoint is paused, stopped, terminal, or no longer the active checkpoint; archived paused/recovery work is still recognized by task ID.
- Specialist execution checks cancellation before starting, before brokered capability use, after brokered reads, and after the model request returns.
- The parent tool records a terminal `cancelled` delegation receipt and discards any late specialist answer rather than relaying it as completed work. The central chat timeline emits a distinct `[SPECIALIST:CANCELLED]` event rather than misclassifying it as RETURNED or FAILED.
- Added tests for active parent pause/stop visibility, archived parent cancellation, cancelled delegation receipts and blocking capability dispatch when cancellation is already present.
- Limitation: the current provider request is synchronous and cannot yet be forcibly interrupted mid-HTTP call. The late response is discarded when it returns; bounded provider timeouts/interruptible transport and genuinely asynchronous child workers remain follow-up work.
- A child does not inherit parent write permissions. Parent authorization, confirmation and evidence gates remain authoritative.


## Implementation notes — durable specialist worker job ledger

- Added `OrionWorkerJobStore`, a separate AtomicFile-backed ledger for bounded specialist jobs linked to an exact parent checkpoint and optional parent objective node.
- Job kinds are intentionally limited to analysis, read-only research, trading analysis and task planning. This ledger cannot represent phone actions, messaging, shell commands, account mutation or exchange orders.
- Enqueue uses a parent-scoped idempotency key; duplicate submissions return the original job rather than creating a second provider charge.
- Claims are serialized and limited to one active job on-device. The caller must verify that the exact parent checkpoint is still running immediately before execution; an unverifiable parent cancels the queued job.
- Parent cancellation marks its queued/running/paused child jobs cancelled. Terminal transitions reject stale or duplicate completion.
- A process restart converts a persisted RUNNING job to NEEDS_REVIEW. It is not automatically re-run because the provider may have completed before the process died.
- Added Robolectric tests for idempotent enqueue, persistence, single-worker claims, fail-closed parent checks, parent cancellation, duplicate completion and interrupted-provider recovery.
- This is durable job orchestration state, not yet an independent model executor. The next implementation slice must connect a bounded worker runner, provider timeout/budget controls and chat timeline events without allowing background workers to bypass the parent Agent's capability/approval policy.


## Follow-up hardening — shared ledger and brain visibility

- Worker-store instances now share one process lock and reload the AtomicFile ledger before each operation, preventing stale per-instance caches from overwriting another instance's enqueue/cancel/claim.
- Parent-state callbacks are evaluated outside the worker-store lock. This avoids lock inversion with AgentCheckpointStore pause/stop, which persists the parent transition before cancelling its child jobs.
- Interrupted RUNNING jobs are converted to NEEDS_REVIEW once when the file is first opened in a new app process. Ordinary reads and state transitions in the same process no longer misclassify a live RUNNING job as interrupted.
- Added tests for multiple store instances, one-active-job enforcement across instances, simulated process restart, and central-chat status summaries.
- Orion's central chat system prompt now receives a compact durable worker-job receipt summary alongside the existing specialist delegation ledger. It is explicitly told not to imply queued work has executed.
- Parent objective lifecycle now distinguishes `waiting_for_children` from terminal completion. When a chat turn ends with queued/running child jobs, the parent checkpoint remains pending and visible instead of being marked completed.
- A waiting parent remains valid only for its exact task ID. Pause, stop, archive/replacement, and unknown-parent checks still fail closed; an archived waiting parent cannot authorize late child work.
- Child completion is persisted first. The worker ledger then checks for remaining queued/running siblings and asks the active matching parent checkpoint to complete only after the final child receipt is durable. If siblings remain, the parent stays waiting.
- Added a Robolectric lifecycle test covering chat-turn completion with a queued child, parent waiting state, exact-parent validity, child claim/completion, and final parent closure.
- A true asynchronous provider-backed worker is still not enabled. The durable lifecycle is now prepared for it, but the next slice must implement the bounded WorkManager executor, provider timeout/request budgets, cancellation checks immediately before and after provider calls, and same-conversation result delivery. Do not claim independent background AI execution until that executor is implemented and verified.

## UI/UX restructuring — central companion workspace

**Product rule:** the main chat is the single conversational front door and the home screen is its operational overview. Feature destinations remain available, but they must not fragment Orion into unrelated assistants or imply that a tile is a working integration.

### Design system and reference research

- Use an opaque black / graphite / silver / white palette, restrained borders, clear typography and conventional card surfaces. Do not reintroduce teal as the primary accent, oversized floating controls or liquid-glass surfaces.
- Follow Jetpack Compose unidirectional data flow: UI renders state and emits intents; durable repositories and the shared task coordinator own state transitions. Avoid a second UI-only task state.
- Use Google's **Now in Android** (Apache-2.0) as an architectural reference for Compose screen structure, state ownership, accessibility, responsive layouts and testability. Do not copy its product UI wholesale; adapt the patterns to Orion's single-brain workflow.
- Follow Android's Material 3 adaptive navigation guidance when supporting larger windows. Keep the compact phone navigation deliberately small and thumb-reachable; add the adaptive navigation dependency only when the expanded-window layout is implemented and tested, rather than increasing APK/dependency cost speculatively.
- Keep the UI light for the Redmi A3 Pro (4 GB RAM): avoid continuously animated dashboards, expensive blurred layers, eager loading of every destination and unnecessary background polling.

### Main information architecture

1. **Orion Chat** — default home action and single conversation for questions, commands, images, voice handoff, approvals and results.
2. **Work** — active objective, step timeline, delegated specialist jobs, waiting/blocked/approval states, recovery and completed receipts. All cards deep-link back to the originating chat/task.
3. **Trading Desk** — market context, watchlists, chart/analysis, journal and proposals. Read-only analysis can be delegated; real-money orders stay behind risk checks and explicit confirmation.
4. **Specialists** — agent roster, scoped assignments, progress, results, pause/stop and permission boundaries. The UI must distinguish a registered agent from an executing worker.
5. **Memory & learning** — review and correct durable memory, task lessons and provenance; no silent promotion of uncertain observations.
6. **Explore / capabilities** — research, browser, device tools and integrations, grouped by task rather than a long unstructured list.
7. **Settings & trust** — provider connections, permissions, capability grants, privacy, background execution and diagnostics.

### Home-screen implementation slice

- Replaced the generic assistant landing page with a central-brain entry surface and an explicit **Open Orion Chat** primary action.
- Added direct workspace entries for Tasks & Workflows, Specialist Team, Trading Desk and Memory & Learning, using the already-wired destination callbacks.
- Kept voice as a secondary interaction surface labelled as using the same brain.
- Added research, teach-workflow and system-access entry points under connected capabilities.
- Added explanatory copy separating local setup status from proof of live service/background-worker health.
- Preserved the existing black/graphite/silver visual language and compact bottom dock.

### UI completion gates (still open)

- [ ] Replace the static home workspace tiles with live, repository-backed objective, child-worker, approval and recovery summaries. No fabricated counts or generic READY indicators.
- [ ] Make the chat timeline render durable plan/step/specialist events as compact, expandable activity receipts, with retry/stop/review actions bound to real task IDs.
- [ ] Make Work, Specialists and Trading Desk detail screens share the same objective and event models; avoid duplicate task stores.
- [ ] Consolidate chat/voice navigation and conversation identity so switching surfaces does not create a second conversation or lose the active objective.
- [ ] Add accessible semantics, large-text support, keyboard/IME handling, loading/empty/error states and screenshot/UI tests.
- [ ] Validate on 720×1640 Android 15/16 and low-memory conditions; remote CI does not replace Redmi device testing.

### External engineering references

- Android Compose UI architecture: https://developer.android.com/develop/ui/compose/architecture
- Material 3 adaptive navigation: https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation
- Now in Android reference implementation (Apache-2.0): https://github.com/android/nowinandroid
- WorkManager persistent work and execution limits: https://developer.android.com/reference/androidx/work/WorkManager

**Honest status:** this commit restructures the home screen and wires the already-existing chat, tasks, agents, trading and memory destinations into one workspace model. It does not yet complete the live task dashboard, unified chat activity receipts, navigation consolidation or physical-device acceptance.

## Trading Desk + central Trading Coach chat integration

Claude's trading work is preserved as two complementary layers, not collapsed into a single chat destination:

- The canonical trading backend already present on this branch is `TradingDesk.kt`, routed through `OrionTradingDeskCapabilityExecutor` and the shared `OrionCapabilityAdapters`. This owns strategy state, market/calendar research, setup scans, backtests, forecasts, conditions/news, paper trading, position monitoring, trade-history sync, habit evidence, derivatives and chain research. It remains the source of truth; the new UI must not create a second playbook or watchlist database.
- Claude's PR #121 introduced a separate `TradingDeskActivity` built around an older `TradingDeskStore` / `TradingPlaybook` model and a specialist-only coach transcript. That implementation is not copied wholesale because doing so would fork strategy/watchlist state and keep the user outside the central Orion brain.
- Added a dedicated `TradingDeskActivity` workspace using the existing canonical `TradingDesk` model. It provides desk status, watchlist context, on-demand read-only setup scans, market/news conditions, position review, paper-trade settlement, accepted/pending strategy views, performance evidence and a link to account/execution controls.
- The workspace's coaching actions open `MainChatActivity` with a task-specific initial prompt. This reuses the central Orion conversation, current provider routing, chat history, image/chart attachment flow, main capability dispatcher and parent-objective lifecycle; it does not create a parallel coach chat history.
- The home workspace Trading entry now opens the dedicated desk. The old coach activity's desk-status link opens the new desk. The older trading controls screen returns to central Orion Chat rather than the legacy specialist transcript.
- Strategy acceptance is an explicit user action and only changes the saved strategy playbook. It does not place an order. Real order execution remains separately gated by the execution capability, read-only preference, risk controls and per-order confirmation.

### Trading integration acceptance gates (still open)

- [ ] Verify the exact-head CI build and trading capability integration tests after the new Activity/manifest/navigation changes.
- [ ] Verify every desk action uses the canonical `TradingDesk` store and shared capability route; no legacy duplicate TradingDeskStore is introduced.
- [ ] Verify chart upload from the central chat, with trading analysis context and no separate coach transcript.
- [ ] Verify forex and crypto market data failure states, stale-data labels, timezone/session handling and macro-calendar freshness; built-in calendar hints must never be presented as a verified live economic calendar.
- [ ] Verify position, history, habit and derivatives actions with no API key, testnet keys, and read-only real-account keys.
- [ ] Verify strategy proposal → user accept/reject → matcher/backtest evidence, without silently enabling execution.
- [ ] Verify execution remains blocked when the pack is off, read-only is enabled, risk gate fails, or explicit confirmation is declined.
- [ ] Test all screens and chart-chat handoff on the Redmi device; remote CI is not device acceptance.

**Honest status:** the dedicated desk surface and central-chat handoff are now implemented on the roadmap branch. This is not yet a verified end-to-end trading release; exact-head CI and the device/account acceptance matrix remain outstanding.

## Claude Trading Desk companion integration (PR #148 ported in PR #152)

Claude's `trading-desk-11` work was squash-merged by PR #148 into `upgrade/nanomuse-chat-foundation`. PR #152 ports the companion and desk additions onto this autonomous-companion roadmap line while retaining the newer central-brain and objective architecture.

- The Trading Coach surface now includes the animated character with idle/thinking/listening/speaking states, finger-following eyes and mood reactions; the dot-matrix orb and hologram head remain selectable companion looks.
- Voice controls include one-shot microphone input, hands-free Talk, spoken replies and the optional offline wake-word path. These controls remain subject to Android microphone permission and installed wake-word model availability; no always-on background listening is claimed.
- The Trading Desk now has a setup-health card, activity feed, section jump navigation and the companion's richer PnL/mood snapshot.
- Desk backup/restore now carries coach learning notes as well as the playbook, practice data and journal. Import deduplicates notes and rejects credential-like content. API keys, tokens and safety-state controls remain excluded.
- Coach-screen desk links, home navigation and Soul Engine navigation now open the canonical `TradingDeskActivity`. Trading discussion actions from the desk continue in `MainChatActivity`; the legacy coach activity remains available as a specialist surface, not the canonical brain.
- The background desk watcher remains disabled by default. Claude's source branch had changed that preference default to enabled; this was intentionally not adopted because merging must not silently activate background monitoring.
- Existing real-order controls, per-order confirmation, read-only preference, policy packs and risk gates remain unchanged.

### Verified integration status

- [x] PR #148 confirmed merged into `upgrade/nanomuse-chat-foundation`.
- [x] Companion UI, activity feed, setup-health checks, memory backup/restore and navigation ported into the roadmap integration branch.
- [x] Exact-head PR #152 CI passed: Kotlin unit tests, debug APK build, APK package/signature validation and artifact staging.
- [x] Port the companion's visual state into the canonical `MainChatActivity` without creating a second transcript or objective store (PR #153; exact-head Android CI passed).
- [ ] Verify microphone permission, Talk stop/resume, wake-word model availability, interruption recovery and background restrictions on the Redmi A3 Pro.
- [ ] Verify backup export/import on-device with existing coach notes and a journal containing duplicate records.
- [ ] Complete end-to-end trading account matrix and confirm every live-data view shows stale/unavailable states honestly.

**Honest status:** Claude's companion/desk additions are integrated and CI-verified on the roadmap line. PR #153 now embeds the companion in the actual central Compose chat header, maps the central runtime state to its animation, and persists the selected drawn appearance. The coach specialist surface retains its expanded dot-matrix/hologram views. Live Voice remains the existing dedicated flow; this change does not add an always-on microphone. Physical Redmi acceptance, Talk/Command continuity checks, microphone interruption recovery and backup export/import verification remain open.


## Stage 3 execution foundation update (2026-10-31)

- Added `OrionAgentDelegation.restore(context, manifestId)` so a future WorkManager process can reconstruct the persisted specialist assignment from its durable manifest ID rather than relying on an Activity or in-memory Agent.
- Restoration fails closed for missing/malformed IDs, unknown specialist profiles, unknown access levels, invalid tool names, malformed parent/objective identifiers, blank scopes and out-of-range budgets.
- Restored access and memory scope are checked against the canonical specialist profile policy; persisted tool grants must be a subset of the profile's canonical read-only tools. A corrupted preference record cannot widen a research specialist into messaging or other write capabilities.
- Added Robolectric tests for round-trip manifest restoration, unknown/malformed IDs and tampered capability grants.
- Existing dependencies are reused: WorkManager KTX 2.9.1, OkHttp 4.12.0, AtomicFile-backed worker ledger, SharedPreferences delegation receipts and the existing provider runner. No additional dependency was added.

**Evidence status:** source and tests are committed on the roadmap branch. Exact-head CI has not yet been observed for these commits, so the new tests are not claimed as passing. The independent provider-backed WorkManager worker, shared cancellable provider request budget, durable originating-chat result event and process-death end-to-end test are still outstanding. Stage 3 remains IN PROGRESS.


### Central-brain delegation hardening follow-up (2026-10-31)

- Tightened manifest restoration so only a receipt explicitly marked `running` can be reconstructed for execution; completed, failed and cancelled receipts remain historical evidence and cannot become execution grants.
- Background-restorable manifests now require a non-empty parent checkpoint ID. Orphaned work is rejected before a future worker can reach provider execution; the worker must still validate that exact checkpoint is current and running immediately before the request.
- Added regression coverage for terminal-receipt and orphaned-manifest rejection. Existing tamper coverage confirms persisted tool-scope widening is rejected.
- These are source changes and committed tests, not a claim that Gradle/CI or physical-device tests have passed. The provider-backed WorkManager executor, shared request cancellation/budget enforcement, and durable originating-chat result delivery remain the next Stage 3 implementation gates.
- Central-brain invariant: specialists may produce bounded evidence, but only the parent Orion conversation owns user-facing continuity, final interpretation, memory updates and any consequential action approval.


### Specialist receipt integrity update (2026-10-31)

- Receipt completion is now one-way: only a running receipt can be finished. Duplicate or late completion cannot overwrite a terminal outcome.
- Manifest restoration rejects persisted capability-call budgets above the canonical specialist profile budget.
- Regression tests cover widened capability budgets and duplicate completion, in addition to terminal receipts, orphaned manifests, and widened tool scopes.
- Source and test changes are committed. Gradle/CI and physical-device verification have not yet been observed. Background specialist execution remains incomplete until the scheduled executor, guarded provider path, cancellation handling, and parent-chat result delivery are implemented and tested.


### Specialist job-to-chat continuity foundation (2026-10-31)

- Extended the durable specialist job receipt with optional, validated references to its exact delegation manifest and originating conversation session. Older ledger records remain readable because both fields are optional.
- The references are persisted and restored with the job, so a future process-independent executor can recover the correct policy receipt and return its result to the originating chat rather than whichever conversation happens to be active later.
- Malformed manifest/session references are rejected at enqueue time; restored references are validated before being exposed. These identifiers are routing metadata only and do not grant execution permission.
- Added Robolectric regression tests for process-restart preservation and malformed-reference rejection.
- This is a continuity prerequisite, not the worker itself. No WorkManager provider execution or chat delivery is claimed. The next slice must implement a real, idempotent scheduler/worker that loads these references, validates the live parent checkpoint and manifest, uses the existing bounded/cancellable provider transport, then writes a durable result event to the recorded session.


### Stage 3 — provider-backed background specialist execution (2026-10-31)

- Added a real WorkManager OrionSpecialistWorker and unique-work scheduler. WorkManager input contains only the durable job ID; the worker reloads the job, originating conversation ID, manifest and exact parent checkpoint before it can call a provider.
- Added the central-brain queue_agent capability. It accepts built-in coder, writer and planner with no external tools, research with public web/news search only, and Trading Coach with public Bybit ticker data only. The worker reconstructs these narrowly allowlisted read-only capabilities without an AccessibilityService instance; private account data and trading writes are never available.
- Queueing requires an active exact parent checkpoint and a stored originating chat session. Repeated identical requests under the same parent receive a deterministic idempotency key by default; callers may supply an explicit key.
- The worker claims only its exact job ID, enforces the existing single-active-job limit, requires network connectivity, reuses the existing Nim transport with a bounded three-request HTTP budget, and cancels its active model-provider connection when WorkManager or the parent checkpoint stops it. Public research lookups use their existing short network timeouts; late results are discarded before model analysis.
- Parent pause/stop/replacement is checked before claim and throughout provider execution. The worker can dispatch only allowlisted public web/news search and unauthenticated public Bybit ticker reads. It cannot dispatch phone actions, messaging, shell, account reads, account changes or trades. Its result is analysis evidence only.
- Added a RESULT_PENDING durable state. Provider output is persisted before the delegation manifest is finalized; a restart between either commit resumes finalization without calling the model again. A process interruption during the provider call becomes NEEDS_REVIEW, never an automatic replay.
- Successful results are written as stable-ID chat events to the exact originating session, including archived sessions, without activating or changing the user's current conversation. Event delivery is idempotent and can retry after result persistence.
- Added specialist_job_status for the central brain to retrieve the full bounded result, and included the durable specialist queue summary in task_list context. The parent checkpoint is deliberately left in waiting_for_children after a worker completes so the central brain, not the specialist, owns interpretation and final completion.
- Added regression tests for exact-job claiming, single-worker exclusion while a result is staged, staged-result restart recovery, review-only ambiguous outcomes, parent-review ownership, and idempotent delivery into the original archived conversation.

**Scope and verification boundary:** this implements the first provider-backed background execution path, not unrestricted autonomy. Automatic parent re-entry and physical-device reliability remain open. Background coach execution is limited to a public ticker quote, not full private-account or multi-source trading-desk analysis. Exact-head Android CI must pass before calling the source/test integration build-verified; Redmi A3 Pro acceptance is still required. Android/HyperOS may defer WorkManager execution, so the app must not promise always-on or immediate completion.


### Central-chat result awareness and delivery follow-up

- The central Agent system prompt now receives the durable specialist queue summary on every normal chat turn, including full job IDs and an explicit instruction to retrieve completed results with specialist_job_status, treat them as untrusted evidence, and retain ownership of parent completion.
- task_list exposes the same queue state, while specialist_job_status returns the full bounded saved result for an exact ID.
- MainChatActivity now loads lifecycle events directly from the SQLite conversation repository, rather than depending on PhoneAiService being alive. It refreshes events on chat resume, so background results remain visible when the user returns.
- A completion notification is emitted only after the result event has been persisted. It directs the user back to the original Orion conversation and explicitly says the parent task is not yet complete.


- Hardened the bounded manifest ledger so it evicts historical terminal receipts first and refuses new work if every slot is occupied by a running manifest. A queued worker's active policy receipt can no longer be silently evicted by later synchronous specialist calls.


## Implementation notes — autonomous repair and extension lifecycle policy

- Added a deterministic repair/extension state model: diagnostic findings carry component, severity, evidence references, timestamp and status; repair proposals carry risk class, idempotency, rollback, approval and test-run evidence; extension drafts carry source URLs, license evidence, declared capabilities, sandbox test receipt and rollback plan.
- Automatic retry is limited to one attempt and only for reversible, idempotent work with a rollback path; critical findings are never auto-retried.
- Model-generated repairs cannot auto-apply unless they are reversible, idempotent, rollback-capable, have a passing test receipt and do not require owner approval.
- Repair lifecycle transitions distinguish test failure, test pass, owner review, applied, verified and rolled back states. A failed verification after application resolves to rollback rather than success.
- Extension validation requires HTTPS source evidence, license identity/evidence, declared capabilities, a successful sandbox test run and a rollback plan. Activation is separately gated on the awaiting-owner state and explicit approval.
- Added unit coverage for retry restrictions, repair test evidence, failure/rollback transitions, extension source/license/test/rollback requirements and owner approval.
- This is the deterministic policy foundation, not yet the complete autonomous repair runner. Remaining work: connect findings to actual provider/tool/worker failures; persist repair cases and evidence; surface diagnosis and approval activity in main chat; invoke the real extension sandbox from the lifecycle; and verify rollback/disable behaviour end-to-end.


## Follow-up — central-brain local self-diagnostics

- Added `OrionSelfDiagnostics`, a provider-free, observational inspection of the live tool registry/policy-pack mapping, enabled skill tool references, generated-extension source-hash integrity, service runtime summary and unresolved task checkpoint state.
- Wired its bounded report into `OrionCentralBrain.contextFor`, so Talk/Command's shared executive context can see local structural faults before planning. The report is explicitly scoped: it does not claim remote provider health, network reachability, Android permission grants, or physical-device reliability.
- Diagnostics do not instantiate a new runtime, schedule WorkManager jobs, mutate state, change permissions or execute repairs. They only surface evidence and instruct the brain to verify any repair with a real test.
- Added tests for finding evidence requirements and report severity/health semantics.
- Remaining: persist findings/repair attempts in a user-visible repair ledger; emit findings directly from failed dispatch/provider/worker paths; add a chat-visible self-diagnosis and repair activity timeline; invoke real extension sandbox tests and verify activation/rollback receipts.


## External architecture research consulted

These sources informed the design patterns; Orion's implementation remains Kotlin-native and does not copy their runtime code:

- LangGraph fault tolerance: retry only classified failures, apply bounded timeouts/retries and route exhausted failures into explicit recovery handlers — https://github.com/langchain-ai/docs/blob/main/docs/src/oss/langgraph/fault-tolerance.mdx
- LangGraph interrupts: persist workflow state before pausing for owner input, then resume the same thread/checkpoint — https://github.com/langchain-ai/docs/blob/main/docs/src/oss/langgraph/interrupts.mdx
- LangGraph functional execution: isolate side effects, persist task outputs and use idempotency so a resumed workflow does not repeat completed writes — https://github.com/langchain-ai/docs/blob/main/docs/src/oss/langgraph/functional-api.mdx
- OpenHands agent architecture: keep the reasoning/action loop, tool orchestration, context management and security validation as explicit responsibilities — https://github.com/OpenHands/docs/blob/main/sdk/arch/agent.mdx

Orion's Android implementation deliberately adapts these concepts to its existing AgentCheckpointStore, ToolGate, AgentPolicy, WorkManager, restricted Python extension sandbox and owner approval controls rather than introducing a second agent runtime.


## Follow-up — Talk diagnostics and visible activity receipt

- Explicit phrases such as "self diagnose", "self-heal" and "system audit" now request a fresh local diagnostic snapshot from the main Talk chat. Ordinary greetings and unrelated questions do not trigger the inspection.
- The diagnostic run writes a bounded `self_diagnostic` lifecycle event into the active conversation. The existing chat event renderer displays the receipt in the same timeline as tool and approval activity; it does not create a separate dashboard-only record.
- The event records counts and diagnostic scope, not raw arguments, credentials, source code or private payloads. The report clearly states that external provider health and Android permission/device reliability were not tested.
- Added regression coverage for explicit diagnostic intent and durable event persistence in the real SQLite conversation repository.


## Persistent personal memory, auto-indexing and context-window upgrade

- Added a first-class routine memory category and a deterministic local auto-indexer for explicit user preferences, repeated routines, ongoing projects and goals from typed and voice turns.
- Auto-indexed observations are stored as pending owner-review proposals with source role, confidence and reason. Sensitive candidates are rejected before storage; exact duplicates do not downgrade an already approved memory. This is not passive surveillance and does not save arbitrary chat messages as semantic memories.
- Fixed prompt selection so unreviewed or sensitive pinned entries are filtered before ranking; they can no longer crowd approved memories out of the context window.
- The existing SQLite FTS4 triggers already index every persisted message. The central brain now also retrieves a few relevant archived conversations for substantive new requests, while excluding the active session to avoid duplicating current turns.
- Added ContextWindowBudget, which prioritizes active objective/checkpoint, central contract, relevant memory and episodic evidence before lower-priority inventory. Its token estimate is intentionally conservative and provider-neutral; exact provider tokenization and output-token reservation remain future work.
- Increased the approved profile-memory allowance in typed chat and voice context.
- Added unit tests for extraction, sensitive rejection, context priority, budget clipping and omission handling.
- Detailed architecture and open-source research choices are in docs/ORION_MEMORY_CONTEXT_MULTIMODAL_ARCHITECTURE.md.
- Multimodal reality check: the app already has accessibility/OCR/TFLite and voice foundations. ML Kit GenAI availability is device-gated and should not be assumed on the Redmi A3 Pro; LiveKit Agents needs a separate Python/Node service for the full agent runtime. Neither has been added as a mandatory dependency.
- Remaining: temporal fact validity/correction history, retention/expiry, provider-specific token budgets, stable cross-provider attachment/perception references, optional on-device semantic embeddings, and physical-device realtime voice/perception acceptance.


- Follow-up: hands-free voice now rehydrates recent turns from the same provider-neutral SQLite conversation history used by typed chat, and persists recognized user text plus Orion's final reply back into that searchable history. Raw audio is not stored by this path. A repository test covers the transcript → FTS index → adjacent-turn recall path.


- Follow-up: optional Gemini Live now receives approved profile memories and recent non-sensitive typed/voice turns as data-only context. Its final user/model transcript segments are persisted into the same SQLite full-text index, and explicit user preferences/routines can create reviewable proposals. Raw microphone audio remains unpersisted. This remains an audio-first Live mode; camera/video streaming has not been implemented.


---

## Stage 4 continuation — 2026-11-01

The Stage 4 implementation has been extended beyond memory/context foundations:

- Added the Settings-linked Sensor & Context Permissions dashboard with separate revocable grants and expiry.
- Added a foreground-only accelerometer/gyroscope collector. It persists only a coarse motion hint for 15 minutes and injects it into the central brain only while the matching grant remains active.
- Added a conversation-linked multimodal evidence index for image attachments, manually requested non-sensitive screen observations and explicit voice-session final transcripts. It stores references/digests/metadata, not raw media. Chat deletion removes the corresponding evidence index rows.
- Added transport-aware provider capability profiles. HTTP chat profiles distinguish image-capable models from dedicated Gemini Live audio/video capability; unknown models use conservative budgets. Context packing accounts for multimodal content parts and preserves the newest user turn where possible.
- Tightened MCP SSE response selection to require the matching JSON-RPC request ID. The existing connector remains the single MCP transport and its registered capability executor remains the execution route.

### Still open — do not mark Stage 4 complete yet

1. Dynamic exact-model capability refresh from provider metadata where available; exact tokenizer/context accounting remains provider-specific.
2. Wire the remaining sensor scopes to dedicated Android permission-backed collectors, with visible status, retention and pause/revoke behaviour. Current dashboard clearly labels consent-only scopes.
3. Expose memory version history, confidence and expiry editing in the Memory UI, not just the underlying store.
4. Add a secure evidence-reference resolver that can reopen a still-permitted image attachment for a later model turn; video frame capture/streaming is not implemented.
5. Expand MCP to resources/prompts and run protocol conformance tests.
6. Verify permission flows, battery behaviour, notification delivery, memory pressure and sensor sampling on the physical Redmi A3 Pro.

Latest code validation must be based on the exact PR head workflow result; CI is not a substitute for device validation.

- Gemini Live now receives the shared evidence index and consented short-lived motion context under its own bounded prompt budget. Its current PhoneAI route is audio-only; video input is deliberately not advertised until camera capture and frame transmission are implemented.

- Connected the existing safe notification listener to the NOTIFICATIONS grant: safe in-memory summaries now enter the central brain only with both Android listener access and explicit Orion consent; revocation clears summaries and blocks actions.

- Added on-demand, consent-gated battery/charging/network/thermal snapshots, approximate last-known location (10-minute freshness, ~1 km rounding), and recent app foreground-duration summaries (Usage Access required, sensitive apps excluded, not persisted). These snapshots are supplied to the central brain only while their Orion grants are active.

- Memory Activity now surfaces prior versions and owner-editable expiry windows; expired entries are retained locally but filtered out of prompt context. Permanent deletion removes the memory's version history and graph metadata.

- Connected approved routine memories to the central brain as non-nagging suggestions and added owner-selected once/daily/weekly reminder scheduling from Memory Activity. Recurrence requires the separate reminder grant and Android notifications; revocation or loss of routine eligibility cancels future work.

- Gemini Live's bounded system instruction now receives the same active conversation evidence and currently granted sensor/device/location/usage/notification/routine summaries as the central brain; its PhoneAI route remains audio-only.
