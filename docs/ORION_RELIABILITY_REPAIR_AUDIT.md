# Orion reliability repair audit

This branch contains source-level reliability changes plus explicit remaining device/provider acceptance work. This document is not a claim that physical-device validation has passed.

## Implemented in this branch

- **Native tool argument validation:** `OrionNativeToolDispatcher` now resolves each call against `ToolRegistry` and validates its JSON arguments with `OrionToolContractCodec` before invoking the executor. Unknown keys, missing required values, and type mismatches are rejected before dispatch. Antigravity's MCP bridge already performs the same contract validation at its separate provider boundary.
- **Explicit media-app routing:** if the user names YouTube Music or Spotify and that app cannot accept the request, Orion returns an explicit error. It no longer silently falls through to a YouTube web search.
- **Answer-oriented web research:** `web_search` and `live_web_search` now search first, safely extract up to three public HTTPS source pages, and return source URLs plus bounded page text in the chat. They do not launch browser UI. `chrome_search`, `browser_navigate`, and `open_url` are documented as explicit navigation/interacting routes.
- **Neural playback focus:** Kokoro `AudioTrack` playback now requests transient Android audio focus, ducks on a competing transient duck request, stops on exclusive/transient loss, and abandons focus after completion or interruption.
- **Antigravity context recovery:** prompt size is capped at 32,000 characters, compaction preserves the newest tail, and a context-overflow retry at 12,000 characters is allowed only before any Orion tool callback has dispatched.
- **Read-only retry:** Agent dispatch now applies `RecoveryPolicy.retryRead` with bounded exponential backoff to READ-only tool failures. Writes and consequential operations are not automatically replayed.

## Still requires implementation and validation

### Voice echo / unnatural playback
- Current `Voice.listen()` stops TTS before starting `SpeechRecognizer`, preventing intentional playback/listening overlap.
- Android `SpeechRecognizer` owns its capture pipeline here; Orion cannot attach `AcousticEchoCanceler` to that hidden recorder. A true AEC fix requires moving to an owned `AudioRecord` capture pipeline with an audio session, attaching AEC where supported, and testing device-specific audio routes.
- Neural PCM is streamed to `AudioTrack` on a serialized worker and now owns transient playback focus.
- AEC remains unresolved: Android `SpeechRecognizer` owns the capture pipeline, so app-level `AcousticEchoCanceler` cannot be attached to that hidden recorder. A true AEC fix requires an owned `AudioRecord` capture path and a compatible recognition transport. This requires device-specific implementation and testing.

### Slow / unstable voice
- Current Kokoro telemetry measures total generation-plus-streaming time and real-time factor, not separate model inference, first-PCM latency, and playback drain. Split metrics and sentence scheduling changes need device measurements before tuning buffer sizes or chunk boundaries.
- Must test interruption, Bluetooth/speaker route changes, long replies and concurrent playback on the POCO F3.

### Antigravity context overflow
- Antigravity CLI has no native persistent thread/compaction API equivalent to Codex App Server. Orion now sends a conservative 32k-character compacted prompt and retries a provider-declared overflow once at 12k, only if no Orion tool callback has dispatched.
- Still required: verify the CLI's server-side conversation/session state on the actual account, oversized single-message behavior, and process/session recovery. This is bounded prompt recovery, not native session compaction.

### Tool argument validation
- Shared native dispatch validation added here; Antigravity MCP bridge validation exists on the parent branch.
- Shared native dispatch validation and Antigravity MCP bridge validation are present. Still required: audit and test every provider/transport adapter and alternate executor entry point, including Codex callbacks, API-provider native calls, delegated agents and extension/MCP routes.

### Installed app launching
- Explicitly named media apps now fail closed instead of falling back to web search.
- Still required: POCO F3 package inventory tests, package visibility verification, exact launch-activity resolution and real installed-app launch acceptance. No app launch is claimed verified by CI.

### UI/action consistency
- Still required: trace the actual tool-call ID/name/arguments through dispatch, evidence receipt, service events and chat activity rendering. UI status must derive from the selected call and final dispatch/verification outcome, not inferred assistant text. This branch does not yet alter the chat UI state pipeline.

### Retry/backoff
- Agent dispatch now actually invokes `RecoveryPolicy.retryRead` for READ-only tool calls after transient/ambiguous failures, with bounded exponential backoff. Permission failures and non-transient failures stop without retry; WRITE and CONSEQUENTIAL calls are never automatically replayed.
- `AgentHooks.FailedCallGuard` still blocks an exact failed tool/argument pair after the retry policy is exhausted. This is intentional loop protection, not a global three-retry cap. The separate `TaskLifecycle.MAX_RECOVERIES=3` still bounds full task replans; it is not a tool retry count. Tests must verify the distinction and that users can resume a BLOCKED task safely.

## Required verification

- Run all unit tests and assemble/validate the debug APK for this branch head.
- Test on the physical POCO F3 with the installed voice model, speech service, YouTube Music/Spotify packages and actual configured Codex/Antigravity accounts.
- Record voice latency distributions and audio route behavior; test AEC capability and echo behavior on POCO F3; test app launch package/activity; verify tool UI event receipts; exercise retries under injected transient/permanent/ambiguous failures.
- Verify the web search source extraction against live result layouts and connected API Anything operation discovery/calls. Browser navigation should be reserved for explicit site interaction.
- No merge or physical-device pass is implied by this audit.


## Follow-up: native tool exposure and callback validation

Source changes on the reliability candidate:

- Provider callback tool calls now pass through `OrionNativeToolDispatcher.dispatchCallback`, which checks cancellation, current-request registration, the registered argument contract, and only then enters the existing guarded executor. Both the initial provider callback and follow-up callback use this path.
- The normal returned-tool-call path continues to reject malformed, unavailable, unregistered, and schema-invalid calls before executor invocation.
- MCP discovery/call functions are exposed on ordinary-language requests instead of requiring literal terms such as "MCP" or "API tools".
- Core phone and developer tool families are exposed on every model turn; actual execution remains subject to registered access class, skill allowlists, Android permissions, owner approvals, and executor preflight.
- Added tests for callback invalid-argument rejection, cancellation, unregistered tools, executor feedback, and ordinary-language MCP/phone/developer tool exposure.

Remaining architectural gap: the provider tool window is still bounded (64 by default), and non-core registered tools outside that window are not all native-callable on every turn. The human-readable prompt-signature fallback is not equivalent to a callable provider function. The contract codec still derives JSON schemas from `promptLine`; it has not yet been replaced with typed schema declarations. CI and provider-specific round-trip results for the updated head are pending; no device acceptance is claimed.
