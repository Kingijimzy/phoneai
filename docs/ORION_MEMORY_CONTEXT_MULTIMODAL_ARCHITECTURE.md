# Orion Memory, Context and Multimodal Architecture

Date: 2026-10-31

## Objective

Make Orion feel like one consistent personal companion across typed chat, Command, voice and supported perception surfaces. The main chat remains the executive brain. Memory is provider-neutral and local-first; specialists receive only bounded, policy-filtered context from the same source of truth.

## Memory layers

1. **Turn memory** — the current request and a small sliding window of recent user/assistant turns. Provider changes must not reset this layer.
2. **Working memory** — the active objective, task graph, checkpoint, completed steps, pending step, evidence, blockers and recovery point. This is authoritative for unfinished work.
3. **Episodic memory** — the SQLite conversation repository. Its FTS4 triggers index inserted, updated and deleted messages automatically. Search uses stable session/message references and adjacent-turn context. Archived chats remain searchable until the owner deletes them.
4. **Semantic/profile memory** — owner-approved MemoryStore facts such as preferences, projects, decisions and routines. Generated or automatically observed entries remain pending until reviewed. Sensitive entries never enter model prompts.
5. **Procedural memory** — verified experience and encrypted route memories. These are hints, not permission grants; the current device/app state and policy must be rechecked.
6. **Perception context** — short-lived OCR, image descriptions, voice transcripts and any future camera/video observations. These are not long-term memories unless the user explicitly asks to retain a derived fact.

## Automatic indexing and memory extraction

- Every saved conversation turn is indexed by the existing SQLite FTS4 triggers. The central brain now retrieves a few relevant archived conversations on substantive new requests, excluding the active session to avoid duplicating the live transcript.
- A deterministic local detector recognizes only explicit durable self-descriptions: stated preferences, recurring routines, ongoing projects, goals and direct remember requests.
- It creates a user_observation proposal with source reason and confidence. Direct 'remember that' requests are treated as explicit owner confirmation; inferred preferences, routines, projects and goals remain pending. It does not call a model, retain arbitrary chat, or infer habits from passive surveillance.
- Inferred proposals are pending and are not included in prompts until the owner approves them. Direct remember requests are owner-confirmed. Sensitive candidates are rejected before storage. Exact duplicates are not allowed to downgrade or overwrite an already approved memory.
- The Memory screen remains the review/edit/delete surface. Routine is a first-class memory category.
- Automatic extraction is intentionally conservative. A later phase can add model-assisted extraction behind a review queue, but never auto-approve inferred facts.

## Context-window management

- The central brain assembles the shared context from live capability inventory, active task continuity, relevant history, approved memory, decisions, lessons, extensions and diagnostics.
- ContextWindowBudget estimates tokens at approximately four characters per token and packs by priority instead of blindly truncating the final string. Active objective/checkpoint and the central contract rank first; relevant profile memory and episodic evidence rank above generic inventory.
- This is a provider-neutral conservative estimate, not an exact tokenizer. Provider adapters should later advertise model context limits and reserve output/tool tokens; the shared brain should use the minimum safe request budget.
- Context entries remain data, not instructions. Historical assistant text and learned routes never prove an action succeeded or grant permission.

## Multimodal perception architecture

- **Screen**: retain the existing accessibility tree, ML Kit OCR and optional TFLite detector. Prefer structured UI nodes; use OCR when the tree is sparse; use a detector only when it adds information.
- **Images**: preserve original attachment bytes/URI for the active turn and route them through a provider that explicitly supports image input. Store only a short derived caption or user-approved fact in long-term memory.
- **Voice**: standard hands-free voice rehydrates and persists recognized text plus replies in the same provider-neutral SQLite history as typed chat. The optional Gemini Live session now receives approved profile memory and recent non-sensitive turns, and persists final user/model transcript segments to that same index. Explicit habits from both voice paths can become owner-reviewable memory proposals. Raw audio is not stored by these transcript persistence paths. Hands-free conversation-only restrictions remain separate from Command execution.
- **Realtime audio/video**: keep transport/session state separate from the central brain. Stream media to a supported provider or optional LiveKit agent service; send timestamped, bounded observations and transcript segments to the executive context. Do not persist raw audio/video by default.
- **On-device fallback**: existing OCR and local perception remain the dependable baseline. Android ML Kit GenAI APIs use AICore/Gemini Nano and are available only on a defined set of supported devices; do not assume the Redmi A3 Pro supports them. Check runtime availability before offering local image/audio generation.
- **LiveKit**: an optional future transport for realtime audio/video and interruption-aware sessions. Its Android SDK is a client; the full Agents framework expects a Python or Node agent service. Do not add it as a mandatory local dependency or imply offline capability.

## Research and implementation choices

- Mem0's useful pattern is the retrieve → enrich prompt → answer → store loop, with separate user/session/agent scopes and extraction/deduplication. Orion adopts the pattern but keeps its existing local stores and owner-review gate rather than introducing a remote memory service.
- Graphiti's useful pattern is temporal facts with provenance and hybrid vector/full-text/graph retrieval. Orion already has local FTS, a knowledge graph and decision history. A Python graph server/database would add resource and deployment costs on the target phone, so it is not an Android runtime dependency.
- ML Kit GenAI provides on-device text/image/audio capabilities and embeddings/AppSearch integration on supported devices. Runtime feature checks are mandatory; current support lists do not include every Android handset.
- LiveKit provides open-source WebRTC media transport and agent orchestration. It is suitable as an optional networked realtime path, not a substitute for Orion's local safety gate or central-brain state.

## Current implementation slice

- [x] Add routine to persistent memory categories.
- [x] Automatically propose explicit preferences, routines, projects and goals from typed and voice turns.
- [x] Keep inferred auto-indexed facts pending owner review; honor direct remember requests; reject sensitive candidates and preserve approved duplicates.
- [x] Retrieve relevant archived conversations automatically from the existing local FTS index.
- [x] Pack central context by priority under a conservative estimated token ceiling.
- [x] Increase the approved memory allowance in the chat system prompt.
- [x] Rehydrate and persist standard voice transcript turns in the same searchable conversation history as typed chat.
- [x] Give Gemini Live approved profile memory and recent chat context; persist its final text transcripts into the shared history.
- [x] Add unit coverage for extraction, sensitive rejection, context priority and budget limits.

## Remaining acceptance work

- [ ] Add temporal validity and correction history so changing preferences retain their previous value and current value without destructive replacement.
- [ ] Add memory confidence decay, expiry and user-controlled retention settings.
- [ ] Add semantic embedding retrieval only behind a runtime capability check and a low-memory feature gate; keyword/hybrid retrieval remains the fallback.
- [ ] Add provider-specific context-window metadata and actual token counting where provider SDKs expose it.
- [ ] Ensure image attachments, OCR, audio transcripts and future video frames share stable message/task references across provider changes.
- [ ] Verify voice interruption, reconnect and resume on the Redmi device.
- [ ] Verify privacy/export/delete semantics for memories, transcripts, attachments and derived perception summaries.
- [ ] Test low-memory behaviour on the Redmi A3 Pro; do not assume on-device foundation-model support.
- [ ] Run exact-head Android CI and physical-device acceptance before release claims.

## Open-source references

- Mem0 architecture and memory lifecycle: https://github.com/mem0ai/mem0
- Graphiti temporal context graphs: https://github.com/getzep/graphiti
- Android ML Kit GenAI APIs: https://developers.google.com/ml-kit/genai
- LiveKit Agents and Android SDK: https://docs.livekit.io/agents/ and https://github.com/livekit/client-sdk-android

