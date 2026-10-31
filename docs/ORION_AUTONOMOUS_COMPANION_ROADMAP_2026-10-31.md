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
- [ ] Add provenance, confidence, timestamps, sensitivity labels, retention and correction/deletion semantics.
- [ ] Make memory provider-neutral and accessible to every authorized agent through filtered views.
- [ ] Learn from completed tasks, user corrections, successful workflows and verified failures; do not learn blindly from model guesses or untrusted tool output.
- [ ] Add review-before-save for sensitive or uncertain memories.
- [ ] Build a skill factory: discover gap → research sources → inspect license → draft → sandbox tests → permission review → version → owner-approved activation → rollback.
- [ ] Keep privileged security/policy code outside generated-skill write access.
- [ ] Add periodic memory consolidation with bounded cost and a user-visible audit trail.

**Exit gate:** a lesson learned in one provider/agent is applied correctly by another; a new skill passes tests and remains inactive until authorized; rollback is verified.

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
| Stage 1 — central lifecycle | IN PROGRESS — voice and deterministic research receipts added | actionable live voice uses the shared executor; weather, news, sports, web-search, freshness and market-quote shortcuts now persist task begin/step/result/completion receipts; GitHub/account shortcuts and specialist/background continuity remain |
| Stage 2 — capability intelligence | IN PROGRESS — request-scoped contracts added | relevant capability families receive full contracts while all other tools remain discoverable by compact signatures; exact-head CI and latency/token comparison are pending |
| Stage 3 — specialist delegation | IN PROGRESS — scoped manifests and receipts added | each ask_agent call now receives a parent-linked, least-privilege manifest and a durable delegation receipt; specialist tools are still analysis/read-only and lifecycle controls/independent delegated execution remain |
| Stage 4 — memory/self-evolution | PARTIAL | memory and extension lab exist; canonical cross-agent memory/learning needs integration proof |
| Stage 5 — proactive background | PARTIAL | watcher/runtime/standing-order foundations exist; event pipeline and device delivery need proof |
| Stage 6 — companion experience | PARTIAL | chat, voice and Trading Desk surfaces exist; they do not yet share one execution lifecycle |
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
- Added unit tests for successful receipts, existing-task begin failure, read errors, uncertain evidence writes, cancellation boundaries and failed completion persistence.
- Remaining direct GitHub/account shortcuts are still guarded at dispatch but have not yet been migrated to this checkpoint wrapper.


## Implementation notes — specialist delegation foundation

- Added `OrionAgentDelegation` manifests with a stable delegation ID, parent objective ID, sanitized objective, access class, allowed read-only data capabilities, memory scope and bounded model-call budget.
- Research specialists are limited to public read-only research inputs; trading coaches receive public ticker context only; planners and custom specialists default to analysis-only with no tool capabilities.
- Updated `Tools.askAgent` to persist a delegation start receipt before calling the specialist, inject the manifest into the specialist system context, and persist the result/failure receipt before returning it to the parent.
- A failed receipt write is surfaced as an error; the parent must not treat the delegation as completed.
- Specialist output remains advisory. The parent Agent retains all dispatch, policy, approval and external-action authority. This is not yet independent multi-agent tool execution, parallel delegation, or a complete agent lifecycle UI.
- Added tests for parent linkage, scope restrictions, custom-agent defaults, forbidden external actions, call budget and objective sanitization.
