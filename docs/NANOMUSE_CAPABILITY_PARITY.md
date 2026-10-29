# nanoMuse capability parity audit for Orion

Date: 2026-10-29
Reference: https://github.com/nano-muse/nanoMuse
Orion target branch: upgrade/nanomuse-chat-foundation

## Scope and method

Compared nanoMuse's public README, AGENTS.md, docs/architecture.md, docs/roadmap.md, its built-in tool modules, and the current Orion ToolRegistry contracts. This is a source-level capability audit, not a claim that either project has been tested on the same device. A registered tool contract is not proof that its Android backend, permissions, provider, or UI flow works end-to-end.

## nanoMuse patterns worth adopting

- One goal-oriented agent loop that calls tools, observes results, recovers from unexpected results, and only then summarizes completion.
- A consistent tool contract and a single dispatch boundary, with allow / ask / deny decisions and approval before consequential actions.
- First-class web search plus page fetch, and browser automation that inspects the page after actions.
- Shell and Python execution in a defined workspace/runtime, with bounded execution and an explicit sandbox model.
- Files, email, contacts and calendar tools where the relevant integration is configured.
- Readable long-term memory with remember, recall and forget; relevant memories should surface without requiring the user to repeat them.
- Persistent goals and step status for multi-turn tasks, plus reminders and event triggers.
- MCP discovery for optional integrations, with tool descriptions treated as untrusted and risky calls gated.
- Task progress, auditability and recovery after a tool failure.
- A common capability model across clients rather than a UI that promises actions the runtime cannot perform.

## Orion capability comparison

| Area | Orion source evidence | Gap / required follow-up |
|---|---|---|
| Android screen and app control | ToolRegistry exposes screen inspection, semantic click/type, validation, verification, media, notifications and app launch | Device acceptance tests are needed for each critical flow, especially dialogs, slow screens and post-action verification. |
| Browser automation | In-app WebView tools cover navigation, snapshot, find, click, type, tabs, handoff and verification | Validate real page navigation and recovery on slow/JS-heavy pages; don't treat URL change alone as completion. |
| Public research | web_search, live_web_search, live_news, fetch_url, research_web exist | Add regression tests for current-date headline retrieval, timestamp/source attribution, empty-result handling and bounded alternate search after failure. |
| Shell / Python | Built-in shell, Termux backend, Alpine runtime, and restricted python_exec are declared | Keep the backend distinction explicit. Test harmless diagnostics, approval-gated package installation, missing bridge, exit status and version verification on-device. |
| Termux diagnostics | termux_command only allows exact read-only commands | Corrected its access metadata from WRITE to READ because the tool is explicitly read-only. It must not be used for installs; use the authorized shell backend for mutations. |
| Persistent tasks | task_list, task_add, task_update, task_delete exist | Verify persistence after process death/restart, step updates, resumption after provider/tool errors, and truthful final status. |
| Memory | remember, update_memory, forget, recall, knowledge_search, graph tools and self_review exist | Test retrieval across new chats and provider switching; make sources/age/confidence visible where supported. |
| Skills and specialists | skills_list, ask_agent, create_agent exist | Confirm installed skill execution, durable specialist definitions and errors are surfaced. Listing a skill is not the same as executing it. |
| Integrations | Gmail listing, Telegram, GitHub account/public tools, MCP status/list/call and service capability flows exist | Audit missing or partial calendar/contact/email actions against actual connected scopes; don't claim a connector is available until service_status verifies it. |
| Goals and proactive work | task tools, standing_order, morning_brief and reminders via Android tools are present | No obvious equivalent to nanoMuse's full goals + event-trigger system in the current registry. Add durable goal tracking and scheduled/event-triggered execution only as a separate tested feature. |
| File operations | find_files/open_file, knowledge_search, workspace_read/write exist | These are not yet equivalent to nanoMuse's general workspace file-management/search coverage. Test scope, permissions, large files and accurate path reporting. |
| Auditing and undo | undo_last/undo_status, service_status and capability notes exist | Test that every mutating action reports its result and undo only claims changes that are actually reversible. |

## Fixes made during this audit

5. Added a searchable **Capabilities & Tools** entry to the full-screen chat menu. It lists the actual registered tool names, READ/WRITE classification and descriptions, with a warning that registration does not guarantee the runtime dependency, permission, connector or approval is available. This makes capabilities discoverable from chat without pretending blocked or unimplemented integrations are active.
6. Added **Send Feedback** to the chat menu. It prepares a report with app/Android/device version details and opens Android's share chooser; nothing is uploaded automatically, and the user chooses the destination. Added **Notification Access** to route directly to Android's notification-listener settings. Existing `read_notifications` and `notification_action` contracts remain permission-gated; replying/dismissing notifications is not silently enabled.

1. Changed the `termux_command` registry access class from `WRITE` to `READ`, matching its exact read-only diagnostic contract. This removes a mismatched classification without granting it installation or editing capability.
2. Added a regression assertion that the tool is classified as READ.
3. Earlier on this branch, added narrowly anchored safe Python-version/location diagnostics and tool-routing guidance to choose the Termux/Linux backend when the user explicitly asks about that environment.
4. Added prompt-contract checks for shell backend selection and research/download-size behavior.

## Acceptance tests still required

- [ ] Built-in shell: `python3 --version` returns a truthful result without being misclassified as destructive.
- [ ] Termux: `python3 --version` executes through the configured bridge and reports actual stdout/exit status.
- [ ] Termux package install: a request to install Python uses the Termux shell, reaches the existing approval/setup flow, and verifies `python3 --version` afterward.
- [ ] Missing Termux bridge: Orion states the exact missing setup/permission and does not claim Termux is absent.
- [ ] Research: today's USD/JPY headline is sourced, dated and summarized; on timeout, bounded alternate research is attempted or the real failure is reported.
- [ ] APK research: source page is inspected and APK, additional OBB/data and installed size are not conflated.
- [ ] Tasks: a multi-step task survives app/process recreation and resumes at the failed step without duplicate side effects.
- [ ] Browser: slow navigation, empty page, login/MFA handoff and post-click verification.
- [ ] Memory: remember → new conversation → recall, and forget really removes the selected item from active retrieval.
- [ ] Provider timeout: no fabricated success, no duplicate partial output and no unbounded provider rotation.

## Guardrails

Do not remove Android OS permissions, explicit DENY decisions, cancellation, credential protection, or confirmations for consequential/external actions. Remove accidental blockers by correcting tool metadata, routing, preflight classification and integration wiring. A missing capability must be implemented or reported accurately, not hidden by weakening policy.

## Current verification status

Source changes are pushed to the existing PR branch. Automated CI and physical-device acceptance must pass before these items are considered verified. This document records gaps, not a claim of complete nanoMuse parity.
