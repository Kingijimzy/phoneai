# Orion reliability repair audit

This branch contains the two bounded code fixes below. The remaining work is deliberately marked open until the relevant Android/provider integration can be changed and exercised; this document is not a claim that the full reliability list is complete.

## Implemented in this branch

- **Native tool argument validation:** `OrionNativeToolDispatcher` now resolves each call against `ToolRegistry` and validates its JSON arguments with `OrionToolContractCodec` before invoking the executor. Unknown keys, missing required values, and type mismatches are rejected before dispatch. Antigravity's MCP bridge already performs the same contract validation at its separate provider boundary.
- **Explicit media-app routing:** if the user names YouTube Music or Spotify and that app cannot accept the request, Orion returns an explicit error. It no longer silently falls through to a YouTube web search, which can make an app-launch request appear to have opened the installed app.

## Still requires implementation and validation

### Voice echo / unnatural playback
- Current `Voice.listen()` stops TTS before starting `SpeechRecognizer`, preventing intentional playback/listening overlap.
- Android `SpeechRecognizer` owns its capture pipeline here; Orion cannot attach `AcousticEchoCanceler` to that hidden recorder. A true AEC fix requires moving to an owned `AudioRecord` capture pipeline with an audio session, attaching AEC where supported, and testing device-specific audio routes.
- Neural PCM is streamed to `AudioTrack` on a serialized worker, but this branch does not yet add a complete audio-focus lifecycle or measured capture-to-first-audio latency telemetry.

### Slow / unstable voice
- Current Kokoro telemetry measures total generation-plus-streaming time and real-time factor, not separate model inference, first-PCM latency, and playback drain. Split metrics and sentence scheduling changes need device measurements before tuning buffer sizes or chunk boundaries.
- Must test interruption, Bluetooth/speaker route changes, long replies and concurrent playback on the POCO F3.

### Antigravity context overflow
- Existing branch already bounds recent transcript to 60,000 characters and retains complete recent messages. Antigravity CLI has no native persistent thread/compaction API equivalent to Codex App Server.
- Still required: provider-specific token/character budget tests, process/session recovery tests and clear recovery behavior for oversized single messages. Do not claim native compaction for Antigravity.

### Tool argument validation
- Shared native dispatch validation added here; Antigravity MCP bridge validation exists on the parent branch.
- Still required: audit and test every provider/transport adapter and every alternate executor entry point, including Codex callbacks, API-provider native calls, delegated agents and extension/MCP routes.

### Installed app launching
- Explicitly named media apps now fail closed instead of falling back to web search.
- Still required: POCO F3 package inventory tests, package visibility verification, exact launch-activity resolution and real installed-app launch acceptance. No app launch is claimed verified by CI.

### UI/action consistency
- Still required: trace the actual tool-call ID/name/arguments through dispatch, evidence receipt, service events and chat activity rendering. UI status must derive from the selected call and final dispatch/verification outcome, not inferred assistant text. This branch does not yet alter the chat UI state pipeline.

### Retry/backoff
- Existing `RecoveryPolicy` already classifies transient, ambiguous, permission, unsupported-route and target-missing failures; it allows bounded read-only retries with exponential backoff and does not retry ambiguous writes.
- `AgentHooks.FailedCallGuard` additionally blocks exact repeated tool/argument pairs within a task. The user-reported three-retry behavior has not yet been traced to a single authoritative retry gate; changing this without tracing all call paths risks duplicate writes. Still required: locate every retry counter/guard, consolidate policy, and test transient reads, permanent errors, permission failures, ambiguous writes, cancellation and budget exhaustion.

## Required verification

- Run all unit tests and assemble/validate the debug APK for this branch head.
- Test on the physical POCO F3 with the installed voice model, speech service, YouTube Music/Spotify packages and actual configured Codex/Antigravity accounts.
- Record voice latency distributions and audio route behavior; test app launch package/activity; verify tool UI event receipts; exercise retries under injected transient/permanent/ambiguous failures.
- No merge or physical-device pass is implied by this audit.
