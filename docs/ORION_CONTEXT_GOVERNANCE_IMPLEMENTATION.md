# Orion Context Governance — implementation record

## Implemented in source on the autonomous-companion roadmap branch

### Provider-aware request packing
- The ordinary HTTP provider path now resolves the selected model before constructing the request.
- A conservative provider/model capability profile determines estimated context ceiling, output ceiling and modality flags.
- Oldest conversation turns are dropped first; system/developer instructions are retained within a bounded share; the newest turns are preserved.
- Output tokens are capped to the model profile before request serialization.
- Token estimates use a conservative character heuristic, not provider tokenizers. Unknown models receive a conservative 16k context / 2k output profile. Profiles must be refreshed against each provider's current model catalogue before being treated as authoritative.
- Gemini Live and other dedicated realtime transports have their own request limits and are not yet governed by this HTTP packer.

### Long-term memory, versions, expiry and graph retrieval
- The existing local lexical/hybrid retrieval remains the first-stage RAG mechanism.
- A small on-device SQLite graph stores memory nodes and bounded term edges with confidence and provenance; it is local-only and does not require a remote graph service.
- Approved, non-sensitive memories are indexed into the graph; query-related graph hits are merged into the central prompt memory selection and then pass the same governance filter.
- Previous memory contents are archived before superseding or editing. A bounded 500-entry version history records source, confidence, sensitivity, status, timestamps and reason.
- Expiry metadata can be set/cleared per memory through OrionMemoryLifecycle; expired items are excluded from the central memory prompt.
- Graph retrieval is lexical term/entity expansion, not vector embeddings. No embedding model is assumed on the Redmi A3 Pro. A future vector layer must be optional, quantized/remote, and retain the same local provenance and deletion controls.
- Version history is a local audit trail, not a substitute for a user-facing history UI. Expiry controls still need to be surfaced in Memory settings.

### Sensor-aware context and personal routines
- OrionSensorConsent stores separate, revocable scopes for motion, device state, location, screen context, microphone, notifications, app usage and routine reminders. Grants can expire and default to foreground-only.
- OrionSensorContextSampler reads accelerometer/gyroscope only while explicitly granted and started by a foreground owner; it emits a coarse, throttled motion hint and stops on revocation. It does not start itself or run an always-on background sensor service.
- This does not bypass Android runtime permissions, special app access, foreground-service requirements, notification listener approval, or OEM battery restrictions. Location, notification, app-usage and screen scopes still need their dedicated permission-backed collectors.
- OrionRoutinePolicy turns only owner-approved, non-sensitive, sufficiently confident routine memories into suggestions. OrionRoutineReminderWorker can schedule a one-time reminder only after a separate ROUTINE_REMINDERS grant and an explicit owner-selected timestamp. It does not infer reminder times from routine prose.
- Continuous “monitor everything” is intentionally not enabled. Each sensor/data source needs its own Android permission, visible status, collection cadence, retention rule and pause/revoke control. Android does not provide an unrestricted app-wide observer for every phone activity.

### MCP governance
- OrionMcpPolicy validates server/tool identifiers, intersects discovered tools with an explicit per-server allowlist and requires owner confirmation for mutating or high-impact tool names.
- OrionMcpServerGrant models expiring, per-server grants.
- This is the authorization/contract layer, not a complete MCP transport implementation. Streamable HTTP/SSE client lifecycle, initialize, tools/list, tools/call, resource/prompt support, server configuration UI, credential storage and end-to-end conformance tests remain to be implemented before calling MCP integration complete.
- MCP tools must be adapted into the existing shared task/capability broker. They must never call privileged phone actions directly or expand a parent agent's grants.

### Multimodal continuity
- Existing audio text transcripts and screen accessibility/OCR paths remain the supported sources.
- The context governance layer exposes capability flags for vision/audio/video, but stable cross-modal attachment IDs, image/screen evidence references, audio segment references and video frame sequences are not yet unified under one durable task evidence schema.
- Video/camera streaming is not implemented. Do not infer that a model profile flag means the Android device or selected API route can actually provide that modality.

## Validation boundary
- Add unit coverage for provider budgets, history packing, sensor grant expiry and MCP tool-scope checks.
- CI must pass on the exact branch head before this work is considered build-validated.
- No physical Redmi A3 Pro validation is implied by CI. HyperOS sensor availability, background scheduling, special permissions, memory pressure and thermal behaviour require device testing.


## Continuation update — 2026-11-01

### Sensor-aware context dashboard
- Settings → Access & permissions → Sensor & context permissions opens a visible, per-scope consent dashboard.
- Grants are revocable, expire after 30 days by default, and are foreground-only.
- Motion is the first active sensor collector: accelerometer/gyroscope samples are reduced to coarse movement hints while the dashboard is foregrounded. Raw vectors are not persisted.
- The last coarse hint is retained for at most 15 minutes and supplied to the central brain only while the MOTION grant remains active. Revoking motion clears the cached hint.
- The dashboard labels unimplemented collectors honestly. Device state, passive location, passive screen capture, passive microphone, app usage and notification monitoring are not enabled by these grant switches. Manual screen reads and explicit voice sessions remain governed by their own Android/service permissions and the optional evidence-index grant.
- Routine reminder scope is separate; revoking it cancels scheduled Orion routine reminder work.

### Multimodal continuity
- OrionMultimodalEvidenceStore records local, conversation-linked evidence IDs for selected image attachments, manually requested non-sensitive screen observations, and final voice transcripts when the relevant owner context scope is granted.
- Evidence entries retain modality, source, time, task/session reference, a digest and a short non-sensitive description. Raw screenshots, image bytes, OCR text and audio are not copied into this index.
- The central brain includes the active conversation's evidence index. Deleting a conversation also deletes its evidence references.
- VIDEO_FRAME is reserved in the schema for future frame-level continuity; there is no video capture/streaming implementation yet. An evidence ID does not mean that the media payload remains accessible to a provider; the original permissioned source must be reopened.

### MCP tools integration status
- The existing McpConnector performs Streamable HTTP initialize, initialized notification, tools/list and tools/call; saves bearer tokens encrypted with Android Keystore; supports local endpoint pairing, read/write/communication/system/other scopes; and checks the currently advertised tool catalogue before calls.
- OrionMcpCapabilityExecutor is registered through the shared capability adapter. Non-read actions require an action-specific confirmation and remote tool calls are not retried after ambiguous outcomes.
- SSE parsing now searches events for the matching JSON-RPC response ID rather than accepting the first arbitrary data event.
- MCP resources, prompts, server-initiated notifications, persistent multi-turn streaming subscriptions and full conformance-suite coverage remain future work. The extra duplicate transport class was removed; McpConnector remains the single source of truth.

### Provider-aware context update
- Model capability flags are transport-specific: ordinary HTTP chat profiles expose only known image-capable model families; audio input/output and video are advertised only for the dedicated Gemini Live transport.
- Unknown model IDs use conservative limits; output ceilings are applied before request construction.
- Multimodal messages are estimated by content part, so base64 image strings no longer cause the whole newest turn to be discarded as if they were ordinary text. The newest user turn is retained in a bounded form where possible.
- These are conservative static profiles and character/image-cost estimates, not live provider metadata or exact tokenizer measurements. Gemini Live uses its own transport setup and limits. A future catalogue refresh should store verified context/output limits and modality support by exact model ID and transport.

### Validation
- Exact-head CI must pass after this continuation. No physical-device validation is implied.
- HyperOS background restrictions, notification permission behaviour, screen/voice collectors and long-running sensor behaviour still require explicit on-device testing.
