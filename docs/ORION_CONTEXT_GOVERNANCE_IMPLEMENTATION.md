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
- OrionSensorConsent stores separate, revocable scopes for motion, device state, location, screen context, microphone, notifications and app usage. Grants can expire and default to foreground-only.
- This is a consent/policy layer only. It does not bypass Android runtime permissions, special app access, foreground-service requirements, notification listener approval, or OEM battery restrictions.
- OrionRoutinePolicy turns only owner-approved, non-sensitive, sufficiently confident routine memories into bounded suggestions. It does not schedule or send reminders yet.
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
