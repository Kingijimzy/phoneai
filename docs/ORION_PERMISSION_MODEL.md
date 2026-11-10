# Orion permission and recovery model

Updated: 2026-11-10

## Operating modes and capability access

Conversation mode and permission mode are separate concerns.

- **Talk** is the conversational front door. Orion can answer directly or use registered tools when the current objective requires them. Tool use is governed by the owner's per-tool permission, capability-pack setting, Android grants and action-specific confirmations.
- **Assist** uses the balanced permission preset.
- **Autopilot** uses the autonomous preset for owner-authorized goals. It does not override per-tool ASK/DENY, disabled capability packs, Android permissions, task cancellation, safety hard blocks or action-specific confirmations.
- **Builder** uses the careful preset and asks before write-capability calls unless the owner has explicitly overridden that tool.

The selected preset is applied to the real dispatch path, not just the model prompt.

## Per-tool permissions

Every registered tool has one effective owner policy:

- **ALLOW** — execute when the capability is enabled and all real prerequisites are satisfied.
- **ASK** — pause before that tool call, describe the action, and wait for the owner's explicit approval. A denial means no dispatch.
- **DENY** — refuse that tool. The model cannot bypass the denial by choosing another route to the same prohibited action.

The tool-permission screen cycles Allow → Ask → Deny. The **Careful** preset sets write tools to ASK; **Balanced** and **Autonomous** default to ALLOW; **Read-only** sets write tools to DENY. Explicit per-tool overrides are retained when changing presets.

## Capability packs and platform permissions

- An explicit capability-pack OFF setting is enforced by the registry and dispatch gate.
- A pack's first-launch default is defined by AgentPolicy.defaultOnPacks; optional bridges and proactive features remain opt-in.
- Unknown and unimplemented packs fail closed.
- Android runtime/special permissions, accessibility disclosure, connected-service authentication, external scopes, MCP pairing, target validation and capability health remain independent prerequisites. Orion cannot grant Android permissions on the owner's behalf.
- PLAN mode is a global no-execution mode.

## Action-specific confirmation

Some operations require confirmation even when their tool is ALLOW:

1. Trading Desk write actions.
2. Destructive deletion/removal, including recognized shell and Python file-deletion operations.

If the same tool is configured as ASK, one approval prompt covers both the per-tool request and the action-specific reason; Orion must not ask twice for the same dispatch.

Ordinary authorized actions do not receive duplicate prompts. The app still checks actual Android permissions, setup, validation, cancellation and dispatch results.

## Shell safety

Shell execution is not treated as unrestricted trust. ShellPolicy hard-blocks recognized:
- Root/system and factory-reset wipes.
- Raw block-device writes and formatting.
- Fork-bomb patterns.
- Reads targeting Orion's private key/credential storage.

The command is inspected in original and conservative dequoted forms; it is not silently rewritten. Ordinary workspace and setup commands remain available when the owner has enabled the relevant capability and the selected tool permission allows them. Destructive file deletion is separately owner-confirmed.

## Tool recovery and evidence

- Repeated read-only calls with identical arguments and identical results are warned and then blocked; a changed result resets the no-progress streak.
- A blocked, failed or unverified tool result is not a success receipt.
- New user objectives replace stale objective-graph state; explicit continuation is resolved separately.
- Webpages, notifications, messages, screenshots, files, remote MCP descriptions/results and specialist outputs are untrusted data, never authorization or instructions that can override local policy.
- Secrets remain in the encrypted credential vault. Logs and evidence must use privacy-filtered summaries, not raw credentials or private arguments.

## Credential storage

ApiKeyVault continues to use Android Keystore-backed encryption. Its implementation must remain intact during autonomy and capability changes.
