# Orion integration reconciliation and capability audit — 2026-11-03

## Reconciled release line

A single integration branch has been created:

- Branch: `orion/integrated-release-audit-20261103`
- Starting commit: `35ecb68db084aba6486773e04b6050d8a92693c0` (PR #172 head)
- Included stack, in dependency order:
  1. PR #166 — Stage 4–7 integration repair (69 commits, 44 files)
  2. PR #170 — Alpine/PRoot full CPython execution (18 commits, 7 files)
  3. PR #171 — live platform capability inventory (7 commits, 4 files)
  4. PR #172 — browser session/download hardening and corrected direct-Python approval (9 commits, 3 files)

The live PR metadata confirmed that these four PRs form a clean linear base chain: #170 is based on #166, #171 on #170, and #172 on #171. All four were open and reported mergeable when this audit was written. The release branch is a named snapshot of the top of that chain; it does not merge to `main` and does not close or silently merge other PRs.

## Duplicate and unintegrated open PRs

- #165 is an older Stage 7 integration attempt against the same Stage 4–7 base. Its head is 57 commits behind #166's head and contributes no commits beyond #166. Treat as superseded by #166.
- #164 and #158 point at the same Stage 7 evidence head. Their branch history diverges from #166's candidate; do not merge them on top without a content-level conflict/redundancy review. Their stated scope is represented in #166's activity-evidence/timeline work, but the duplicate PRs remain open.
- #154–#157 are older Stage 4/6/Python branches on a different ancestry. The present release line contains the Stage 4–7 integration base plus the newer Python runtime PR #170, but this audit has not established that every unique commit in #154–#157 is represented. They remain explicitly unverified, not assumed merged.
- #132 and #103 and the older executor/UI extraction PRs (#111–#131) are not individually proven to be fully integrated merely because the newer candidate builds. They require ancestry/content comparison before closure.
- PRs #118–#121 and #145 likewise remain outside the verified four-PR stack unless their changes are demonstrably present in the candidate tree.

## Capability audit

Status labels mean: **Implemented in source** = concrete code exists; **Partial** = useful path exists but the promised end-to-end behavior or hard guarantee is absent; **Not implemented / not verified** = source evidence is absent or the required acceptance test has not been supplied. A green CI run is not device acceptance.

| Promised capability | Current evidence in candidate | Audit status / remaining work |
|---|---|---|
| One Central Brain / unified tool dispatch | PR #166 routes capability families through `OrionCapabilityAdapters.dispatch`; `OrionSoulEngine` builds a capability-aware executive prompt. | **Partial.** The central path exists in this stack; every legacy entry point and every open executor-refactor PR has not yet been proven to use it. |
| Durable tasks, checkpoint/recovery, pause/stop | `AgentCheckpointStore`, `OrionTaskCheckpointStore`, transition policies and task IDs exist; dispatch is gated by checkpoint state. | **Partial.** Persistence primitives and policy tests exist. Full process-death, multi-task, cancellation and background-resume acceptance on the phone remains unverified. |
| Typed activity evidence and live cards | `OrionActivityTimeline`, typed event/evidence models, correlation IDs, chat cards and Stage 7 tests are in PR #166. | **Implemented in source / device-unverified.** Timeline projection is bounded and deduplicates events; verify live stream, replay, cancellation and restored task history on device. |
| Trading Desk chat and analysis | Trading chat kinds, panels, desk/risk models and tests are included in PR #166. | **Partial.** Analysis/alerts are present; automatic orders are not part of this release promise. Validate data freshness, alert correctness and no unintended order path. |
| Full CPython in Alpine/PRoot | PR #170 installs/validates pinned Alpine and launches `python3 -I` in a private workspace with time/output limits and cancellation. | **Partial.** PRoot still shares Orion's Android UID. It is not an OS sandbox. No isolated UID or restricted Binder service exists. |
| Python-to-phone API | `OrionPythonPhoneApi` exposes explicit Kotlin methods to the bundled Chaquopy `orion_exec` runtime. | **Partial.** This is an in-process bridge for the restricted bundled interpreter only. Arbitrary Alpine CPython has no authenticated IPC/RPC bridge to those Kotlin methods. |
| Python network/WebSocket | Exact-host consent, DNS pinning, private-LAN HTTP consent, bounded OkHttp WebSocket sessions and foreground service exist in Kotlin. | **Partial.** These policies protect the Kotlin facade/transport. Arbitrary PRoot Python can use raw sockets and bypass them because it shares the app UID. |
| Python workspace quota | Workspace API enforces per-write limits; `usageBytes`/quota status can detect direct guest writes. | **Partial.** Direct CPython writes can exceed quota while running. There is no hard filesystem quota or continuous kill-on-overrun supervisor. |
| Owner approval for arbitrary Python | PR #172 makes `python_exec` require explicit owner approval; restricted generated extensions remain separate. | **Implemented in source.** This is a policy gate, not OS isolation. |
| Live permission/capability inventory | PR #171 reads runtime permissions, special access, capability packs, skill enablement, watcher switch and WorkManager state on each chat turn. Unknown is not treated as granted. | **Implemented in source / partial operational truth.** Inventory is read-only; a scheduled WorkManager entry or owner switch does not prove a worker process is alive. HyperOS background survival remains device-unverified. |
| Browser session persistence | PR #172 persists up to 8 sanitized HTTPS tab URLs and remaps selected index; unsafe URLs and credential-bearing query keys are removed. | **Implemented in source.** Authenticated cookies remain Android WebView-managed; full session recovery is not guaranteed. |
| Browser agent / online actions | WebView agent can inspect and interact with visible page content; downloads are HTTPS-validated and filenames sanitized. | **Partial.** Not a universal autonomous web agent. Password/file inputs are not auto-filled; file upload uses native picker; login/MFA/CAPTCHA and consequential actions require owner interaction/approval. |
| Tiered memory and retrieve-before-act | Knowledge graph, conversation repository, memory nudge and trust-boundary components exist in the broader candidate history. | **Not fully verified.** No end-to-end acceptance evidence in this stack proves tiered memory retrieval is consistently invoked before every action, survives restart, and respects retention/privacy rules. |
| Self-correction and skill synthesis | Repair lifecycle, extension store and restricted extension runtime exist in prior Stage 4 work. | **Partial / integration unverified.** Safe extension execution is distinct from arbitrary CPython; owner approval and rollback behavior need end-to-end proof. |
| Ambient environmental awareness | Platform inventory reads permission/accessibility/notification state; watcher and proactive worker status are surfaced. | **Partial.** Inventory is not proof of continuous observation. No claim of always-on sensing is made; live Accessibility lifecycle and owner-off enforcement require device acceptance. |
| Persistent scheduler / proactive operation | WorkManager heartbeat and Stage 6 scheduler code are referenced by the integration history. | **Partial / device-unverified.** Android/HyperOS may defer work. Exact-time execution and continuous background liveness are not guaranteed. |
| Personality / behavior / mood dynamics | Companion-life and state components are included in the integration candidate. | **Not independently verified.** Source presence alone does not prove the promised state transitions affect responses consistently. |
| Stage 5 Propose–Probe–Compose | Stage 5 was explicitly put on hold by the owner. | **Out of scope / on hold.** Do not represent it as complete or use it as a release gate. |
| Voice, provider switching, history, permissions and UI | Existing chat/provider/voice/history/settings paths remain in the integration candidate and Stage 7 verification checklist. | **Device-unverified.** Automated compile/tests cannot prove microphone, TTS, provider continuity, UI navigation or permission behavior on the target phone. |

## Release truth

- The four-PR candidate line is now represented by one named branch.
- This operation did not merge to `main`, close other PRs, or claim all historical PR work is integrated.
- PR #172's exact-head CI was previously reported green, but that is not a fresh run against this new release branch after branch creation and does not prove the entire stack's final APK.
- No physical-device acceptance evidence is attached to this audit.
- Stage 5 remains on hold.

## Required next verification before calling this a release

1. Run the full Android unit-test and APK workflow against the exact release-branch head.
2. Compare the unique commits and file-level deltas of #154–#157, #132, #103, #118–#121 and #145 against this branch; port only missing, non-duplicate changes.
3. Add the missing CPython-to-Kotlin IPC bridge or explicitly keep phone APIs unavailable to Alpine CPython. Do not describe the bundled Chaquopy facade as a PRoot bridge.
4. Implement an enforceable isolation boundary and continuous resource enforcement, or retain the explicit owner-approval warning and mark these as incomplete.
5. Complete the Stage 7 device checklist using the APK built from the exact tested SHA. Record PASS/FAIL/BLOCKED per item.

This audit deliberately distinguishes code presence from end-to-end behavior. It is a release-control document, not a claim that all capabilities are finished.
