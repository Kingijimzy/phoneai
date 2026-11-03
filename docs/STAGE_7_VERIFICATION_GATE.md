# Stage 7 — Verification and Release Gate

Stage 7 is a release gate, not another autonomous feature. It separates evidence from assumptions and prevents a green CI run from being described as proof that Orion works on a real phone.

## Required gates

### A. Source and automated checks
- [ ] Verify the tested commit SHA is the exact pull-request head.
- [ ] Android unit tests pass, including Stage 3 memory nudges, Stage 4 scheduler, and Stage 6 subagents. Stage 5 remains on hold and is not a release prerequisite.
- [ ] Bundled Python policy tests pass.
- [ ] Debug APK builds successfully.
- [ ] APK package/application ID, ARM64 ABI, and signing certificate are validated by CI.
- [ ] No test is skipped or silently converted to success to hide a failure.
- [ ] Review the complete workflow log for warnings or skipped verification steps.

### B. Device acceptance — POCO F3 / Snapdragon 870 / Android 13
Use the APK artifact produced by the same successful workflow run. Confirm the device is the POCO F3 / Snapdragon 870 / Android 13 (or update this record if the test device changed). Install it over the existing app only when Android reports the signing key is compatible. If Android reports a signature conflict, stop and investigate; do not tell the user to uninstall and lose app data as a first response.

Record Android version, app version, commit SHA, result (PASS/FAIL/BLOCKED), and a short sanitized note for each check.

- [ ] App installs, launches, and survives close/reopen without an immediate crash.
- [ ] Existing chat history survives process restart; starting a new chat and deleting an archived chat behave as expected.
- [ ] A normal factual question answers without unnecessary tool/permission prompts.
- [ ] Talk mode answers conversationally; Command mode recognizes a clear action request. Switching providers does not erase the conversation context.
- [ ] Granted Android permissions are recognized as granted; a denied/revoked permission is explained and requested only when needed.
- [ ] Browser results open in the intended in-chat browser/popup flow, not an unrelated inbuilt page.
- [ ] MCP/GitHub read-only inspection reports real repository evidence or a clear failure; it must not fabricate success.
- [ ] Python/Linux runtime detection distinguishes available, unavailable, and unverified states; no unsupported runtime is reported as ready.
- [ ] Memory nudge: explicit, non-sensitive preference can be proposed, saved only after review, and recalled after restart. Sensitive material is not suggested for saving.
- [ ] Watcher: status is visible; commands `run subagents`, `show subagent report`, and `show agents` respond without dispatching tools or changing policies.
- [ ] Live watcher: with Core + Proactive enabled and Accessibility Service connected, a service connection and foreground-window transition produce a bounded observation; disabling either pack prevents further live observations; reconnect resumes observation. Verify no screenshot/audio capture is started by this observer.
- [ ] Disabled packs remain disabled after a watcher tick and subagent run.
- [ ] Scheduler: pause/resume/remove and run history persist; verify behavior after app restart and after battery optimization/idle. Record OS scheduling delays rather than claiming exact-time guarantees.
- [ ] Voice: start listening, speak a short test, confirm a visible transcript, response, and audible speech. If any stage fails, record which one.
- [ ] Safety: a write-capable or consequential action still goes through its normal policy/confirmation gate; a report or proposal does not authorize execution.
- [ ] Leave the app running for at least 10 minutes, background/foreground it, and check for freezes, duplicated responses, and unexpected battery/heat behavior.
- [ ] Inspect Android crash logs if a failure occurs; include only sanitized stack traces.

### C. Release decision
- **PASS:** automated checks are green and all critical device checks pass.
- **FAIL:** a reproducible crash, data loss, permission bypass, false success claim, unsafe action, or broken core flow occurs.
- **BLOCKED:** device testing or a required environment is unavailable. A blocked check is not a pass.

Do not merge the Stage 7 stack or label Orion device-verified until gates A and B are evidenced. A successful GitHub build proves only that the checked source compiled, tests ran as reported, and the workflow's artifact checks passed; it does not prove microphone behavior, Android permission state, HyperOS background scheduling, or end-to-end MCP/browser behavior on the phone.

## Evidence record template

| Field | Value |
|---|---|
| PR / branch | |
| Tested commit SHA | |
| Actions run URL | |
| APK artifact / SHA-256 | |
| Device / Android version | |
| Automated checks | PASS / FAIL / BLOCKED |
| Device checks | PASS / FAIL / BLOCKED |
| Known failures | |
| Final decision | PASS / FAIL / BLOCKED |
| Tested at (UTC) | |

## Re-run locally

From the repository root:

```bash
./gradlew testDebugUnitTest assembleDebug
```

CI remains authoritative for the configured PRoot/Python checks and APK signature/package validation. Do not substitute a local build for those workflow steps.


## CI evidence — 2026-11-02

| Field | Value |
|---|---|
| PR / branch | [#157](https://github.com/Kingijimzy/phoneai/pull/157) / `orion/stage-6-proactive-operations` |
| Tested commit SHA | `0b3e6e1122c3ce5358af5dcbd445fad58ec806bc` |
| Android workflow | [Run 41472669020](https://github.com/Kingijimzy/phoneai/actions/runs/41472669020) — PASS |
| PRoot workflow | [Run 41472668674](https://github.com/Kingijimzy/phoneai/actions/runs/41472668674) — PASS |
| APK artifact | `PhoneAI-apk`, artifact ID `13156561084`, 56,656,329 bytes |
| APK SHA-256 | `feb01b07a20bd66d5875e286934bc8baebae5a333d9c915f4235f945ecf3fe2a` |
| Automated checks | PASS — Android unit tests, restricted Python sandbox, debug APK, package/signature validation, pinned PRoot candidate build |
| Device checks | BLOCKED — no live Redmi A3 Pro test evidence available in this CI session |
| Final decision | BLOCKED pending device acceptance |

The Android workflow's configured build/test/Python/package/signature steps all completed successfully on the exact tested SHA. The PRoot workflow also completed successfully on that same SHA. APK release publication was skipped by the workflow; the APK remains available as a CI artifact. These CI results do not establish runtime behavior on a physical device.


## Stage 4–7 integration gate update — 2026-11-02

The current stacked PR dependencies are being reconciled:
- PR #157 (Stage 6) now targets PR #120's navigation branch, inheriting owner controls (#118) and graphite UI (#119).
- PR #158 (activity timeline) remains stacked on PR #157 and inherits that UI/control base.
- Stage 4 repair/Python (#132 → #145 → #154 → #155 → #156) has its own stack. PR #156 was rebased onto the updated #155 parent branch and is now reported mergeable; exact-head CI is still running.
- These stacks are not yet one release candidate. Do not merge or mark Stage 7 accepted until Stage 4 is safely ported into the same candidate and all exact-head checks pass.

### Required physical acceptance
Record results on the confirmed current POCO F3 / Android 13 / Snapdragon 870:
1. Fresh install and upgrade install with preservation of app data and credentials.
2. Provider connection, streaming chat, attachments, cancellation, retry and restored conversation.
3. Accessibility grant/revoke, foreground-window observation, watcher start/stop and owner policy enforcement.
4. WorkManager scheduling, process death, reboot recovery and HyperOS battery restrictions.
5. Scoped Python standard library, workspace read/write, approved HTTP/HTTPS and private-LAN HTTP/HTTPS.
6. WebSocket text/binary lifecycle, reconnect, screen-off behavior and foreground notification.
7. Browser tabs/preview, MCP tool execution, nested task evidence, concurrent tool correlation and event replay.
8. Trading Desk read-only market data/alerts; confirm no automatic order placement.
9. Memory review/approval/rejection, knowledge graph updates and privacy redaction.
10. Live voice microphone permission, interruption, resume and sustained stability.

CI is not a substitute for these device checks. Stage 5 remains on hold; no later stage begins before this gate passes.


## Integration checkpoint — 2026-11-03

The following is an automated-source checkpoint only. It does not satisfy the physical-device gate.

| Field | Value |
|---|---|
| PR / branch | [#166](https://github.com/Kingijimzy/phoneai/pull/166) / `orion/stage7-integration-repair-20261102` |
| Tested commit SHA | `86b068d3838c89ca96e8a4afca7a5767a9488a05` |
| Exact-head Actions run | [Run 41553057509](https://github.com/Kingijimzy/phoneai/actions/runs/41553057509) — PASS |
| Unit tests + debug APK | PASS — `testDebugUnitTest assembleDebug` |
| PRoot + bundled Python policy | PASS |
| APK validation | PASS — package, ARM64 PRoot libraries and v2 signature verified |
| APK artifact | [PhoneAI-apk, artifact 13193072790](https://github.com/Kingijimzy/phoneai/actions/runs/41553057509/artifacts/13193072790), 57,360,059 bytes |
| Artifact SHA-256 | `4b2a731e52a3ea35d99e8d5b3ff4afb979839da477c88becc201eb3831a96751` |
| Release publication | SKIPPED — artifact is available; no GitHub Release was published |
| Physical-device acceptance | BLOCKED — no POCO F3 runtime results recorded |
| Decision | BLOCKED pending device acceptance and remaining integration/security review |

The passing run verifies the exact SHA above, not any later commit. Any subsequent source or documentation commit must receive its own exact-head workflow result before it is treated as verified.

The candidate contains the typed activity evidence model, timeline projection, chat activity cards, event correlation and SQLite persistence/migration, plus sanitizer, timeline, repository and Markdown tests. PR #165 is not a substitute for this candidate: its earlier exact-head build failed because its older snapshots removed Stage 4/6 repository and command-mode APIs. Keep PR #166 as the repair candidate and reconcile only changes that are not already represented there.

### Network-security review note

`network_security_config.xml` currently enables cleartext at the Android application base-config level. The Python HTTP/WebSocket path applies a separate runtime gate (NETWORK consent, exact-host grant, private-address validation, and explicit plain-HTTP permission for local hosts), and its OkHttp client pins the validated DNS answers and disables redirects. However, the manifest-level cleartext allowance is broader than that Python gate: other app networking code is not automatically constrained by the Python policy. Do not claim app-wide cleartext is restricted until every HTTP-capable client is audited or the transport architecture is changed without breaking approved private-LAN HTTP/WS.
