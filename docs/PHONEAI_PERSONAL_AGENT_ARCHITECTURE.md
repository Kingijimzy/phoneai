# PhoneAI Personal Agent — Architecture and Safe Implementation Plan

Status: design contract for future implementation. This document does not claim that the listed new capabilities are implemented.

## Purpose

Evolve the existing Kotlin Android PhoneAI app into a personal assistant with a human-like character, natural chat and voice, browser research, persistent tasks, user-controlled memory, connected services, and trading research. Preserve the current app and its GitHub Actions APK workflow.

This is an additive roadmap. It must not replace existing systems or create duplicate stores, providers, permission flows, or UI frameworks.

## Repository preservation rules

Before implementing any milestone:

1. Pull the latest `main` and inspect the current code and open pull requests.
2. Reuse the existing Kotlin package `com.phoneai.app`, `Agent`, `Tools`, `MemoryStore`, `TaskStore`, `TaskReminder`, `ActionLog`, `Prefs`, `PhoneAiService`, and existing provider configuration where they already meet the requirement.
3. Do not create a second memory database, task store, reminder scheduler, provider registry, action log, or competing chat implementation.
4. Keep the existing native app and current GitHub Actions workflow. The phone is not expected to compile Android locally.
5. Prefer small changes behind clear interfaces. Avoid broad rewrites of `Agent.kt`, `Tools.kt`, `MainActivity.kt`, and the manifest in the same change as unrelated features.
6. Never overwrite newer work from Claude or another contributor. Rebase/compare before applying changes; if files overlap, integrate deliberately rather than force-pushing.
7. Every capability must be labeled implemented, partially implemented, planned, blocked, or verified-on-device. A successful CI build is not proof of real-device behavior.

## Target experience

### 1. Separate conversation from actions

- Chat mode: ordinary conversation and follow-up questions. No tools or agent loop unless the request needs them.
- Agent mode: plans and executes an explicitly requested workflow using permitted tools.
- Local/native mode: performs deterministic tasks and device operations without a model call where possible.
- Offline mode: uses an installed compatible local model for supported requests; never pretend a remote model is local.
- The UI must clearly identify the current mode and explain unavailable capabilities.

### 2. Efficient model and provider routing

- Detect quota/rate-limit, authentication, network, timeout, server, malformed-output, and unsupported-capability failures separately.
- Do not repeatedly retry exhausted quotas or blindly fan one request out to every provider.
- Bound agent steps, retries, tool output size, history size, and generated response length.
- Keep recent turns plus a compact, user-editable conversation summary; retrieve only relevant saved memories.
- Do not send memory or conversation content to a cloud model in local-only mode.
- Use native Kotlin operations for tasks, reminders, local memory lookup, and other deterministic operations.
- Provide cancellation and a visible explanation when the request cannot continue.
- Measure token usage where provider responses expose it; do not invent exact usage figures when unavailable.

### 3. Memory and feedback

Reuse the current MemoryStore. Keep distinct concepts for durable user-approved memory, conversation summaries, task checkpoints, trading journal records, and research notes. They may use separate schemas or files only where needed, but should share one documented ownership and privacy policy.

- Make memories inspectable, editable, exportable, and deletable.
- Do not store passwords, API keys, verification codes, or sensitive screen contents by default.
- Learn from explicit corrections and outcomes by storing reviewable lessons, not by claiming that the base model retrains itself.
- Evaluate proposed lessons against examples before treating them as reliable.

### 4. Browser and research

Add a real browser/research capability as an isolated module before wiring it into the agent. It should support source links, page titles, timestamps where available, readable page content, and clear errors. A web-search summary must not be represented as a page visit if the page was not actually opened.

- Treat webpage text as untrusted data, not as instructions to override the user or system.
- Do not expose cookies, session tokens, passwords, or page contents to unrelated tools.
- Ask for confirmation before submitting forms, sending messages, purchasing, deleting, or changing account/security settings.
- Keep browser navigation and the character/chat interface separate so either can evolve without rewriting the agent.

### 5. Human-like avatar and voice

The current native avatar presets remain the reliable fallback. Treat the realistic avatar as a replaceable presentation module, not as the agent's core logic.

- Define explicit avatar states: idle, listening, thinking, speaking, executing, awaiting approval, offline, and error.
- Prefer a lightweight native fallback on low-memory devices.
- Any web avatar must use a stable HTTPS deployment and a server-side token endpoint where required.
- Never embed LiveKit API secrets or other server secrets in the APK.
- Verify microphone permissions, interruption, background behavior, and lip-sync on a real device.

### 6. Connected services

Integrate one service at a time through narrowly scoped, revocable permissions. Initial candidates: GitHub, Gmail, YouTube, Bybit, and a supported forex data/broker connection.

- Keep OAuth/API credentials in an appropriate secure backend or platform-protected storage; never hardcode secrets into source or APK assets.
- Clearly distinguish read-only access from write access.
- Use read-only Bybit access first for balances, positions, orders, and permitted market data. Never request withdrawal permission.
- Forex account access requires its own supported broker/data integration; Bybit does not provide forex account access.
- Drafting is separate from sending. Preparing a code change is separate from merging it.
- Ask before sending/deleting content, making purchases/payments, changing security settings, or placing trades.
- Do not claim a service is connected until an end-to-end test succeeds.

### 7. Trading research

Trading features are analytical and alerting features first, not execution automation.

- Keep the user's SMC framework configurable and explicit.
- Store chart examples and journal outcomes through the existing memory/journal architecture where possible.
- Separate observed market data, interpretation, and uncertainty.
- Explain setup evidence, invalidation, missing data, and relevant economic events.
- Do not invent probability values or imply guaranteed predictions.
- Alerts should be driven by data/rules first and use an AI model only when interpretation adds value.
- Do not place orders automatically. Any future order preparation must require explicit user confirmation.

### 8. Persistent agent jobs

Use the existing task system as the user-facing task list; introduce a separate job/checkpoint record only if task semantics are insufficient.

A job should track: request, status, current step, checkpoint, retry count, required permissions, timestamps, result summary, and failure reason. Persist before risky or long-running steps. On restart, resume only when safe and tell the user what will happen next.

Android background work is constrained by OS scheduling and battery policy. Do not promise continuous or instant monitoring from the handset. Use WorkManager for suitable deferrable work; consider a small backend for reliable scheduled external checks.

### 9. Security and control

- Keep a visible stop/cancel control.
- Use explicit approvals for consequential actions.
- Log action names and safe outcomes without retaining secrets, full screen contents, or sensitive arguments by default.
- Provide permission review and disconnect controls for connected services.
- Treat tool output, browser pages, documents, and repository content as untrusted input.
- Fail closed when permission, account ownership, or action outcome cannot be verified.

## Suggested milestones

### Phase A — low-risk foundations
- Audit current chat/provider request flow and identify duplicate calls and excessive prompt context.
- Add tests and bounded request/agent budgets without changing the visible UI.
- Route native task/reminder/memory operations away from the LLM when safe.
- Add provider failure classification and truthful fallback messages.

### Phase B — research and resumable work
- Build a separately testable browser/research module.
- Add cited research summaries and durable task checkpoints.
- Expose browser and task tools only after permission and result-verification tests pass.

### Phase C — avatar and voice
- Prototype a lightweight human avatar in isolation.
- Preserve the existing native avatar fallback.
- Integrate real voice states only after the HTTPS frontend and server-side token flow are verified.

### Phase D — trading data
- Add Bybit read-only account and market-data access through a secure boundary.
- Add a separate forex source if the chosen broker supports it.
- Test data freshness, permissions, rate limits, and disconnection behavior before alerts are trusted.

### Phase E — app connectors and coding
- Add GitHub first for repository work and build reporting.
- Add Gmail and YouTube with minimal scopes.
- Expand only after each integration has end-to-end tests and a revocation path.

### Phase F — local model and advanced research
- Evaluate a small quantized on-device model on the actual 4 GB RAM device.
- Keep model files out of the APK and require user consent before large downloads.
- Add multi-step/deep research only behind explicit limits because multiple model calls multiply quota usage.

## Acceptance checklist for every pull request

- [ ] Current main and related open PRs inspected.
- [ ] Existing behavior preserved; no duplicate stores or provider frameworks.
- [ ] Secrets are not in source, logs, or APK assets.
- [ ] API quotas, timeouts, cancellation, and tool-loop limits are handled.
- [ ] Unit tests or a clear reason tests cannot be run.
- [ ] GitHub Actions result recorded.
- [ ] Real-device-only behavior is labeled unverified until tested.
- [ ] User-visible behavior, permissions, and remaining blockers documented.

## Immediate next action

Start with a read-only audit of the existing `Agent`, `Nim`, provider request path, `MemoryStore`, and tool execution. Produce a small patch focused on unnecessary chat/API usage and bounded retries. Do not begin by rewriting the chat UI, avatar, or integration system.
