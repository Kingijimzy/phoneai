# Orion Open-Source Architecture Review
**Date:** 2026-11-01  
**Scope:** Candidate repositories supplied for the Orion autonomous-companion roadmap.  
**Decision:** Reuse verified patterns and narrow components; do not import whole agent platforms or add duplicate runtimes.

## Executive decisions

1. **Remote orchestration:** Keep LangGraph as Orion's one remote graph runtime. Study ByteDance DeerFlow 2.0 for lead-agent/subagent boundaries, sandboxing, per-thread isolation, durable task handling, memory and request tracing. Do not deploy DeerFlow as a second harness beside Orion.
2. **Skill supply-chain security:** Add NVIDIA SkillSpector to the developer/CI security review path before importing third-party skills. Keep skills inactive until reviewed, version-pinned, hash-verified, tested in isolation and explicitly approved.
3. **Codebase understanding:** Evaluate DeusData/codebase-memory-mcp as an optional developer-workstation MCP tool for repository exploration and impact analysis. It is not an Android runtime dependency or Orion's user-memory database.
4. **Engineering workflow:** Use the official mattpocock/skills and garrytan/gstack as workflow references for spec clarification, ticket decomposition, TDD, code review, QA and release checks. Adapt small practices into repository guidance; do not copy an entire agent control plane.
5. **Voice:** Study jamiepine/voicebox for local-first TTS/STT engine routing, chunked speech, voice profiles, REST/MCP boundaries and SQLite-backed local state. Do not bundle its desktop Tauri/PyTorch stack into Android. Keep Android-native speech and low-memory/offline paths first; optional remote voice services must be explicit.
6. **OCR:** Keep ML Kit/TFLite and the current Android OCR path for device work. Treat Baidu Unlimited-OCR-2 as an optional remote/document-processing research candidate only; its reported 3.3B parameters and ~6.9 GB model package make it unsuitable for the Redmi A3 Pro's 4 GB RAM and storage constraints.
7. **Media creation:** Make OpenMontage/HyperFrames optional creative/media capabilities, not core agent infrastructure. HyperFrames is an HTML/CSS/GSAP-to-deterministic-MP4 engine; OpenMontage provides multi-stage production workflows and review gates. These belong on a remote worker or desktop.
8. **Palmier Pro:** Do not integrate as a dependency. It is a macOS 26 Apple Silicon editor with an MCP endpoint; published source through v0.7.6 is GPLv3 while later binary releases are proprietary. It is not suitable for Android and has a licensing boundary.
9. **Cybersecurity skill library:** The repository named mukul975/Anthropic-Cybersecurity-Skills explicitly identifies itself as an independent community project, not affiliated with Anthropic. It contains dual-use/offensive material. Do not bulk-import. Prefer official defensive skills and review each candidate with SkillSpector and a human reviewer.

## Repository-by-repository review

| Candidate | Verified repository | Useful pattern for Orion | Decision / boundary |
|---|---|---|---|
| OpenMontage | https://github.com/Open-Montage-app/OpenMontage | Stage manifests, director/reviewer skills, checkpointed creative pipelines, cost estimation, pre/post-render quality gates, decision audit trail | Optional media-production skill pack on remote/desktop worker. README declares MIT; re-check all transitive provider/model/media licenses. Do not place its large Python/Node/media stack in APK. |
| Deer Flow 2.0 | https://github.com/bytedance/deer-flow | LangGraph lead agent, subagents, per-thread isolation, sandboxed tools, persistent memory, scheduled tasks, request trace correlation | Architecture reference for Stage 5 remote worker. Keep Orion's existing CentralBrain, policy, memory and task IDs canonical; no second harness. README declares MIT. |
| Anthropic Cybersecurity Skills (community repo) | https://github.com/mukul975/Anthropic-Cybersecurity-Skills | Security domain knowledge, framework mapping and defensive investigation playbooks | Independent community project; not an Anthropic official repo. Dual-use content requires strict defensive curation, authorization scope and skill scanning. Apache-2.0 is declared by this repo; validate per-file and bundled-tool licenses. |
| Official Anthropic skills reference | https://github.com/anthropics/skills | Agent Skills format, self-contained SKILL.md conventions and examples | Prefer this official source for general skill format patterns. Its README notes that some document skills are source-available rather than open source; respect per-directory terms. |
| HyperFrames | https://github.com/heygen-com/hyperframes | Deterministic HTML/CSS/GSAP video rendering, agent-oriented skill packages, validation/render loop | Optional remote/desktop renderer; not Android dependency. README declares Apache-2.0 and Node.js >=22. |
| Codebase Memory MCP | https://github.com/DeusData/codebase-memory-mcp | Local structural code graph, call/import/route tracing, impact analysis, MCP query surface | Developer-only optional MCP. MIT declared. Supported binaries are desktop/server OSes, not Android; do not conflate source-code graph with user memory. Verify signed/checksummed releases before use. |
| Matt Pocock Skills | https://github.com/mattpocock/skills | Spec clarification, domain vocabulary, TDD, code review, diagnosis, ticket dependencies, handoffs | Adopt selected engineering practices and skill format. Official repo is mattpocock/skills (not third-party mirrors). MIT declared. |
| GStack | https://github.com/garrytan/gstack | Product/architecture review, QA, security review, release discipline and role separation | Use as an engineering checklist reference. MIT declared; its setup expects a coding-agent workstation (Bun/Node/browser), not the Android runtime. |
| Unlimited OCR | https://github.com/baidu/Unlimited-OCR | Long-document OCR and long-context visual parsing research | Optional server-side OCR candidate after benchmark. Do not ship model weights to phone. Validate the model-card and code licenses separately; measure latency, VRAM, accuracy, privacy and cost before enabling. |
| SkillSpector | https://github.com/NVIDIA/SkillSpector | Static + optional semantic scanning of skills, prompt injection/exfiltration/privilege/MCP risks, bounded ingestion and SARIF/JSON output | High priority for CI/developer skill admission. Apache-2.0 declared; Python 3.12+ means run in CI/remote tooling, not Android. Scanner findings are advisory signals plus hard policy gates, not proof of safety. |
| Palmier Pro | https://github.com/palmier-io/palmier-pro | Agent-to-editor MCP integration | No Orion dependency. macOS 26 Apple Silicon only; source through v0.7.6 GPLv3, later binaries proprietary. Keep license boundary explicit. |
| Hermes Agent | https://github.com/NousResearch/hermes-agent | Skill learning loop, conversation search, scheduled tasks, subagents, provider neutrality and messaging gateway | Study concepts and test skill lifecycle patterns. MIT declared. Do not replace Orion's executor/memory/identity. Current Android/Termux guide reports its package is broken and a fix is in progress; do not recommend as a working Redmi install yet. |
| Voicebox | https://github.com/jamiepine/voicebox | Local-first speech pipeline, multiple TTS/STT engines, streaming/API/MCP boundary, voice profiles and local state | Study and selectively port interface ideas. MIT declared, but its Tauri desktop app and ML inference stack are not a mobile drop-in. Test individual model licenses and hardware needs. |

## Stage 5 implementation consequences

### 5.1 Typed task protocol
Use a versioned schema shared by Kotlin and Python. Add JSON Schema/Pydantic validation, unknown-field policy, size limits, idempotency, explicit deadline, parent/child IDs, capability request IDs, checkpoint cursor, and W3C trace context. Keep model-generated values untrusted.

### 5.2 Remote worker
Use a single authenticated worker service and LangGraph. Use PostgreSQL checkpointer in production; SQLite only for local development. Add per-owner/per-device tenancy, per-task isolation, bounded concurrency, timeout/retry policy and worker lease/heartbeat. Do not expose the worker publicly without TLS, device authentication, rate limits and authorization.

### 5.3 Secure capability bridge
Remote graph nodes submit signed/authorized capability requests; Android re-resolves the registered capability and current permission/grant state. Android ToolGate/AgentPolicy remains the sole authority. Bind approvals to task ID, action digest, arguments digest, scope, expiry and one-time nonce. Reject replay, scope expansion, stale approvals and unknown capabilities. Consequential actions still require Android owner approval.

### 5.4 Checkpoint, cancellation and recovery
Persist graph checkpoint before yielding control. Model cancellation as a durable state, propagate parent cancellation to children, and make nodes cooperatively cancellable. Every side-effecting node needs an idempotency key and reconciliation path; never retry an uncertain write blindly. Resume only from the last confirmed checkpoint and re-verify outputs.

### 5.5 Distributed tracing
Propagate W3C traceparent and tracestate only after validation. Create OpenTelemetry spans for worker request, graph run, node, specialist, capability request, Android receipt and approval wait. Do not export prompts, credentials, screen contents, raw audio, tool arguments or memory passages. Local SQLite remains the owner-visible event ledger.

### 5.6 Skill and tool admission
Create a skill admission pipeline:
- Fetch immutable commit/tag and record source URL, commit, license and content hashes.
- Scan with SkillSpector (static first; optional semantic review).
- Review permissions, scripts, network, shell, file writes, MCP tools and prompt-injection instructions.
- Run tests in an isolated sandbox with no secrets/network by default.
- Require explicit owner promotion; bind approval to exact content hash.
- Re-scan and invalidate approval whenever content changes.
- Keep imported skills disabled by default and expose revocation/deletion.

## Hardware and privacy constraints

The Redmi A3 Pro remains a lightweight trusted endpoint. Do not bundle LangGraph, desktop MCP graph binaries, large OCR/TTS models, Docker, Node.js video renderers or multi-agent sandboxes into its APK. Use existing on-device OCR/voice where practical; move expensive processing to an explicitly configured remote worker. Screen/audio/sensor observations remain consent-gated, minimized, short-lived where appropriate, and never silently exported.

## Validation gates

- Protocol contract tests pass in Kotlin and Python against the same fixtures.
- Authentication, tenant isolation, replay rejection and capability-scope tests pass.
- Worker restart resumes from checkpoint without duplicate side effects.
- Pause/resume/cancel tests cover parent and child tasks, approval waits and timeouts.
- Approval receipt is action-bound, expiring, one-time and rejected after argument mutation.
- Trace IDs correlate Android → worker → graph → capability result; sensitive payload redaction tests pass.
- SkillSpector scan and license/provenance review are required before skill activation.
- Android exact-head CI passes; physical Redmi acceptance remains separate.

## Primary references

- DeerFlow README and architecture: https://github.com/bytedance/deer-flow ; https://github.com/bytedance/deer-flow/blob/main/docs/ARCHITECTURE.md
- NVIDIA SkillSpector: https://github.com/NVIDIA/SkillSpector
- Official Anthropic skills: https://github.com/anthropics/skills
- Community cybersecurity skills notice: https://github.com/mukul975/Anthropic-Cybersecurity-Skills
- Codebase Memory MCP: https://github.com/DeusData/codebase-memory-mcp
- Matt Pocock skills: https://github.com/mattpocock/skills
- GStack: https://github.com/garrytan/gstack
- Hermes Agent: https://github.com/NousResearch/hermes-agent
- Voicebox: https://github.com/jamiepine/voicebox
- Unlimited OCR: https://github.com/baidu/Unlimited-OCR
- OpenMontage: https://github.com/Open-Montage-app/OpenMontage
- HyperFrames: https://github.com/heygen-com/hyperframes
- Palmier Pro: https://github.com/palmier-io/palmier-pro
