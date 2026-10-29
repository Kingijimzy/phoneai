# Soul Engine backend: implementation truth

This document describes the Android implementation on the feature branch, not a claim of physical-device acceptance.

## Real in this branch

- **Capability snapshot:** `OrionCapabilityDiscovery` derives tool availability from the registered tool list, enabled capability packs, and known permission/setup facts. `SoulEngineActivity` reports core-disabled, service-stopped, limited, or monitoring states rather than treating an open dashboard as proof that all workflows work.
- **Shared action gate:** agent tool dispatch goes through `AgentPolicy` / `GatePolicy`, with the registered `ToolRegistry`. A remembered route or accepted proposal is not an approval grant and cannot dispatch a tool by itself.
- **Session continuity:** `AgentCheckpointStore` persists the active task, bounded step evidence, and uncertain in-flight actions. Uncertain mutations require outcome inspection rather than blind replay.
- **Persisted Soul state:** `SoulEngineState` persists a sanitized goal, Talk/Command mode, a bounded watch-label list, reviewable proposals, lessons, and last-tick/outcome metadata in app-private preferences.
- **Evidence-based route candidates:** only a durably completed checkpoint with at least two successful distinct eligible tool steps can create a pending route proposal. Sensitive account/payment/credential goals are excluded. Repeated matching evidence increments the proposal count.
- **Proposal inbox:** Accept/Edit/Reject updates the persistent proposal state. Accepting does not execute a route, install executable code, change tool permissions, or weaken guardrails. Proposed tool names are intersected with the current registry.
- **Cross-request hints:** sanitized lessons and accepted proposal titles can be retrieved by lexical overlap and included in the Soul planning context. They are hints only; current capabilities and policy are rechecked.
- **Privacy boundary:** stored Soul labels pass through redaction and sensitive-content omission. The state store intentionally does not persist tool arguments, raw tool results, full screen text, credentials, OTPs, or message bodies.
- **Self-extension:** the existing declarative skill and restricted Python extension flow remains separate. It requires sandbox evidence and user approval; Soul learning does not silently promote extensions.

## Still stubbed or incomplete

- **Scheduler/cron:** the existing `OrionRuntime` is an event-driven bounded work queue, not a user-configurable cron scheduler. Morning brief, price alert, and recap schedules are not claimed as completed by this change.
- **Memory nudge:** no periodic “save this preference/fact?” nudge is implemented here. Rate-limited, opt-in nudges remain future work.
- **Route execution fast path:** accepted routes are searchable planning hints, not a macro runner. The runtime does not replay saved tool sequences automatically.
- **Propose–Probe–Compose:** this change records completion evidence and allows review; it does not yet run controlled candidate-vs-baseline probes or automatically compose a refined prompt/tool harness.
- **Subagents:** no parallel subagent runtime is added.
- **Alive tick loop:** no always-on background sensing loop is introduced. Service-connected state means the Android service is attached; it does not mean Orion is continuously observing the screen or running unattended actions.
- **Device acceptance:** source/CI tests do not prove behavior on the Redmi A3 Pro. Cold launch, Soul Engine entry, proposal review, process-death restoration, and permission-gated dispatch still need on-device validation.

## Inspiration adopted

- **Hermes Agent:** durable procedural route hints, learning candidates after successful multi-step tasks, searchable cross-session hints, and explicit review before adopting reusable behavior.
- **Harness²:** separate harness state from model weights; preserve lessons as data; use behavioral evidence as a reason to propose a change; do not silently weaken guardrails; keep candidate adoption reviewable.
- **Intentionally not ported:** multi-platform gateway, desktop-specific executors, unbounded tool packs, recursive research on every tap, or unrestricted generated code.

## Acceptance gates

1. Android unit tests and the APK workflow pass on the exact commit.
2. Regression tests cover persistence, proposal status transitions, sensitive-label omission, and successful multi-step candidate creation.
3. Physical-device checks confirm the dashboard does not crash, restored state is accurate, and all actions remain behind the same policy gate.
4. Keep PR #103 in draft until the required checks pass.


## Watcher follow-up

- `PhoneAiService` wakes the persisted Soul queue on a bounded timer while the accessibility service is alive; the watcher does not claim to run after Android kills the service.
- Added `SoulWatch.run`: every tick performs a local capability audit, stores sanitized readiness evidence, and can add one deduplicated maintenance proposal for a real limitation.
- If the user has explicitly enabled the Proactive pack and saved standing orders, a tick may execute at most one due read-only standing order. Standing-order dispatch now passes the shared `AgentPolicy.gate`; a blocked gate skips the action and does not consume the schedule.
- The watcher does not auto-enable packs, grant permissions, edit source code, replay routes, trade, send messages, or weaken guardrails. Accept/Reject/Edit stays in the proposal inbox; actual code changes still require a reviewed development workflow.
- The five-minute in-service wake-up is best-effort, not an Android exact alarm guarantee. Doze, force-stop, battery optimization, and OEM task killing can delay or stop it.
