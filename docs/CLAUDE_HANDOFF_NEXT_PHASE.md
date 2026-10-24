# Claude Handoff — PhoneAI Next Phase

This file is a coordination note for any coding agent working on this repository. Read it before implementing the personal-agent roadmap.

## Current baseline

PhoneAI is an existing Kotlin Android app in package `com.phoneai.app`. It is not a greenfield project. Preserve its current UI, chat behavior, permissions, and GitHub Actions workflow.

The repository roadmap documents these existing foundations (verify the latest `main` before relying on this list):
- Provider/model selection for chat and vision.
- Existing `Agent` and `Tools` execution path.
- `MemoryStore` and memory management UI.
- `TaskStore`, `TaskReminder`, and task UI.
- `ActionLog` with privacy-limited local action history.
- `Prefs`, `PhoneAiService`, avatar presets (Orion, Nova, Atlas, Lumen), voice/hands-free options, screen vision/recording, and accessibility-based actions.
- GitHub Actions workflow for debug APK build and release publication.

These are existing foundations, not proof that every path is bug-free or verified on a real device. Check code and test results before extending them.

## New design documents

- `docs/PHONEAI_PERSONAL_AGENT_ARCHITECTURE.md`: overall personal-agent direction and phased plan.
- `docs/BROWSER_AUTOMATION_AND_SKILLS.md`: browser automation, reusable skills, safety model, and phased acceptance criteria.
- `docs/JARVIS_2_ROADMAP.md`: existing roadmap and implementation status.

These documents are additive specifications. If they are on an unmerged pull-request branch, they are not automatically part of `main`; open the linked PR or fetch the branch before relying on them. Review current open PRs and merge status first.

## Required conflict-avoidance workflow

1. Fetch/pull the latest `main` before starting.
2. Inspect open PRs, branches, recent commits, and changes already made by Claude or another contributor.
3. Compare changed files before editing. Never force-push, reset shared work, or overwrite someone else's uncommitted/remote changes.
4. Keep changes narrowly scoped and additive. Avoid changing the same files as another active PR unless integration is deliberate.
5. Prefer one milestone per PR. Do not combine chat routing, browser automation, avatar, trading integrations, and skills in a single rewrite.
6. Reuse the current `Agent`, `Tools`, `MemoryStore`, `TaskStore`, `TaskReminder`, `ActionLog`, `Prefs`, `PhoneAiService`, and provider setup. Do not add duplicate stores, routers, chat screens, reminder schedulers, or permission systems.
7. Do not mark a feature complete because a prompt, UI, stub, or interface exists. Require working implementation, tests, and clearly labeled device verification.
8. Preserve the GitHub Actions cloud APK build; do not require the user to install Java, Gradle, or Android SDK in Termux.

## Recommended implementation order

### First: chat quota and reliability
Audit the actual provider call path and agent loop. Add tests before changing behavior. Reduce repeated conversation context, bound output/steps/retries, classify quota vs transient failures, deduplicate repeated tool calls, and route deterministic local operations away from the model. Keep normal chat separate from phone-control/agent mode.

### Second: browser research, read-only
Build a small isolated browser/search interface first. Add citations, timeouts, cancellation, source validation, prompt-injection defenses, and compact page extraction. Do not begin with unrestricted browser clicking, form submission, or account automation.

### Third: skill registry
Start with reviewed declarative skill manifests and built-in skills. Each skill must declare purpose, inputs/outputs, tools, scopes, risk, approval gates, budgets, version, source/license, and tests. Skills cannot grant their own permissions or override system policy. Do not download and execute arbitrary scripts or APKs.

### Fourth: approved browser actions and resumable jobs
Add allowlisted navigation/click/type operations with checkpoints and verification. Require confirmation before form submission, sending, publishing, purchases, deletion, security changes, or financial actions.

### Later: avatar, service connectors, and trading
Implement these in isolated phases. Bybit starts read-only; forex needs a separate supported broker/data source. Never embed server secrets in the APK. Keep existing native avatar presets as a fallback.

## Security and quality requirements

- Treat browser pages, repositories, files, and third-party skills as untrusted input.
- Keep API/OAuth secrets out of source, APK, logs, and model prompts.
- No unapproved trades, purchases, message sends, deletion, or security-setting changes.
- Log safe action outcomes without secrets or full sensitive screen contents.
- Add tests for permission boundaries, hostile page instructions, loops, quota exhaustion, cancellation, and recovery.
- Report exact tests and build results; disclose blockers honestly.

## Immediate instruction

Before coding, post a concise audit of current `main` and open PRs, identify the files the proposed change would touch, and state how it avoids conflicts. Start with the smallest tested chat-efficiency improvement. Do not implement browser automation or a skills runtime in the same PR as chat refactoring.
