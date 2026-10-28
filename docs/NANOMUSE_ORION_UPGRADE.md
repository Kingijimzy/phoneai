# Orion × nanoMuse Upgrade: Chat-First Foundation

Status: implementation plan and source map; this document does not claim the app has been changed or tested.

## Product direction

Upgrade Orion in place, using `Kingijimzy/nanoMuse` as an architectural reference while preserving Orion's existing visual identity: near-black canvas, graphite surfaces, silver/white accents, restrained motion, compact opaque cards, and no teal/liquid-glass redesign. Optimize for low memory and responsive use on 4 GB Android devices.

The chat screen is the primary workspace. Voice, phone actions, browsing, terminal, skills, memory, tasks, and specialist functions should enter through the same conversation and shared execution layer rather than becoming disconnected mini-apps.

## Source map

### Orion

- `app/src/main/java/com/phoneai/app/MainChatActivity.kt`: full-screen chat activity, event/service bridge, provider/model controls and chat lifecycle.
- `app/src/main/java/com/phoneai/app/OrionComposeChat.kt`: Compose chat presentation, message rendering, composer, mode controls and in-chat activity cards.
- `app/src/main/java/com/phoneai/app/OrionComposeShell.kt`: home/navigation shell and app-level palette.
- `app/src/main/java/com/phoneai/app/Theme.kt`: shared neutral color tokens.
- `app/src/main/java/com/phoneai/app/ChatHistoryStore.kt`: bounded local transcript and archive persistence.
- `app/src/main/java/com/phoneai/app/Agent.kt`, `AgentRunner.kt`, `OrionSoulEngine.kt`: agent reasoning and execution integration points.
- `TaskLifecycle.kt`: task state model; must be connected to real execution, not remain an isolated state machine.
- `CapabilityRegistry.kt`: source of truth for available, blocked, missing and unhealthy capabilities.
- `LinuxRuntimeInstaller.kt`: inbuilt Alpine/PRoot runtime integration.

### nanoMuse reference areas

- `android/src/android/app/src/main/java/io/github/nanomuse/home/MainChat.kt`: persistent main-conversation identity.
- `android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt`: chat composition and interaction patterns.
- `android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` and adjacent extensions: state and conversation operations.
- `android/src/android/app/src/main/java/com/openminis/app/ui/theme/ChatColors.kt`: semantic color tokens.
- `nanomuse/agent/core.py`: agent loop reference.
- `nanomuse/sentinel/`: policy gate, approvals and audit reference.
- `AGENTS.md` and architecture documentation: project-level conventions and component boundaries.

Use these to understand design decisions. Do not wholesale copy implementation: confirm licensing compatibility first. The nanoMuse fork is GPL-3.0; Orion's repository currently has no declared license, so keep the first implementation independently authored until the project's licensing decision is explicit.

## Implementation sequence

### Phase 1 — Chat interface and state boundaries

1. Keep the existing Orion black/graphite/silver palette; centralize semantic colors so chat, activity cards, buttons and status indicators cannot drift.
2. Make the chat screen the primary task workspace: compact header, clear new-chat/history actions, readable user/assistant messages, image preview/removal, accessible composer, send/stop state, and Talk/Command mode.
3. Keep expensive or verbose details collapsed by default. Tool activity, errors, durations, and approvals should be expandable inline rather than filling the transcript with oversized cards.
4. Preserve transcript restoration, archive/delete semantics, image attachment handling, provider/model selection, and service callbacks. Do not replace working storage with a new parallel store.
5. Add UI tests or focused state tests for empty chat, restored chat, image attached/removed, send/stop, history/new chat, and mode switching.

### Phase 2 — Shared agent/task execution

**Lifecycle consistency hardening:** the live loop now validates state transitions instead of unconditionally overwriting the phase, and only advances the success path through VERIFY after the existing completion-evidence gate allows it. Failed tool results remain blocked for inspection; they are not replayed automatically. Integration tests and latest-commit CI are still required.

**Implementation started in the Phase 1 branch:** `Agent.kt` now drives `TaskLifecycle` through planning, capability checks, authorization, execution, verification, recording and blocked/recovery states on the live guarded tool-task path. The existing `OrionExecutionLoop`, `ToolGate`, `CapabilityRegistry`, action checkpointing and per-tool approval checks remain in place. This is not yet a claim that every direct shortcut or specialist-agent path is lifecycle-managed; those routes need separate mapping and tests.

1. Trace the live path from chat input through `Agent.kt` / `AgentRunner.kt` and identify the actual tool dispatcher.
2. Integrate `TaskLifecycle` into that path so the UI reflects real planning, running, verification, blocked, recovering, completed, failed and cancelled states.
3. Resolve tool availability from `CapabilityRegistry`; do not let prompts claim a tool is usable when its dependency or permission is missing.
4. Route browser, terminal, phone actions, and skills through one guarded execution boundary with approval, cancellation, bounded retries, redacted output and duplicate-side-effect protection.
5. Return structured execution events to the chat UI; do not infer successful completion from a tool call merely being issued.

### Phase 3 — Adaptive recovery and verification

1. Classify failures (permission, missing dependency, timeout, HTTP status, invalid output, unsupported action, provider failure).
2. Select a materially different and permitted alternative when appropriate.
3. Verify the original goal with observable evidence; if not verified, report the blocker honestly.
4. Bound retries and execution budgets; detect repeated tool/action loops.
5. Store only useful, evidence-backed lessons, with controls to inspect or delete them. Learning must not silently weaken security policy or rewrite production code.

### Phase 4 — Performance and reliability

- Keep Compose recomposition and animation work bounded; avoid unnecessary background polling and duplicate WebViews.
- Respect Android lifecycle and cancellation; release image/bitmap/WebView resources.
- Test build and unit tests in CI, then install and exercise on a physical Android device before claiming device acceptance.
- Keep the built-in Alpine environment primary for Linux commands; Termux remains optional, never a silent required dependency.

## Acceptance criteria

- Existing chat, provider, image, history and service behavior remains intact.
- Chat styling remains black/graphite/silver/white.
- A task's visible status comes from actual task state and execution events.
- Capability status is truthful and consistent across chat, settings and agent prompts.
- Failed actions produce a diagnosis and a bounded alternative or a clear blocker.
- Sensitive operations retain explicit approval and are never retried in a way that duplicates side effects.
- Tests/build results are reported with their actual status; no unverified APK or device-test claims.

## First coding target

Start with Phase 1 by reviewing `MainChatActivity.kt` and `OrionComposeChat.kt` together, then make small, reviewable changes that preserve the current callbacks and storage contracts. Avoid a wholesale rewrite of the 45 KB chat renderer before its state and event boundaries are mapped.
