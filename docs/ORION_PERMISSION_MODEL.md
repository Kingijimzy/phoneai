# Orion permission and recovery model

Updated: 2026-11-05

## Default execution

- Agent tool permission defaults to **ALLOW**.
- Global ASK, ALLOWLIST and TRUSTED approval modes are removed from the live agent path. Legacy saved values resolve to ALLOW.
- Per-tool ASK is legacy storage only and resolves to ALLOW. An explicit per-tool DENY remains a denial.
- PLAN remains the explicit global no-execution mode. Disabled capability packs, Android OS permissions, external account scopes, local MCP pairing, cancellation and capability prerequisites remain enforced.
- Once the required capability and platform permissions are present, tools execute immediately without a per-call approval prompt.

## The only three confirmations

One shared confirmation surface is reserved exclusively for:
1. Restart.
2. Shutdown.
3. Toggling Wi-Fi.

The confirmation is checked at the dispatch boundary, including the MCP `computer_settings` route. Messages (Telegram, SMS, WhatsApp, Signal and Discord), notification replies, remote writes, payments, memory deletion, shell commands, routine replay, recovery and skill tool calls do not receive generic confirmation prompts.

## Shell behavior

The shell policy contains only these three Ultron-compatible Windows command-string checks:
- `format c:`
- `rmdir /s /q c:\windows`
- `del /f /s /q c:\windows`

There are no SAFE/ASK/BLOCKED shell tiers. The previous root/system wipe, raw block-device, fork-bomb, curl/wget-to-shell and private-data shell blocks have been removed. Built-in shell still runs in its managed workspace; Termux and Linux/PRoot use their selected backends without the old shell-risk classifier. Android filesystem and runtime boundaries remain properties of their respective backends, not approval tiers.

## Logging and arguments

- ActionLogStore persistence is disabled and legacy action history is purged when Agent starts.
- AgentPolicy audit logging is disabled.
- No action arguments or argument fingerprints are written to OrionEvidenceLedger.
- Shell commands and outbound communication actions are excluded from durable action/activity receipts.
- Task verification evidence remains distinct from an owner-facing action history; it stores no action arguments.
- The agent decision-record mutation tool is not registered.

## Undo coverage

- **UndoStore:** saved-memory add, update and delete. Undo checks the expected current state before restoring.
- **WorkspaceUndoStore:** registered workspace create/write/overwrite/delete/move operations. Undo checks the current file state and verifies the restored state. Intervening edits cause a conflict instead of overwriting newer work.
- Shell commands and sent messages are not undoable and are not logged.

## Injection boundary

Text and data from webpages, notifications, messages, screenshots, files, remote MCP descriptions/results, tool outputs and specialist/worker results are **untrusted data**, never instructions or authorization. They cannot change policy, grant permissions, request secrets, or authorize additional actions. Only the owner and the app's local policy/settings can do that.

## Credential storage

ApiKeyVault continues to use AES-GCM encryption backed by a non-exportable Android Keystore key. Its implementation is unchanged by this permission-policy update.
