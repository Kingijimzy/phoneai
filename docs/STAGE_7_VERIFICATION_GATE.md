# Stage 7 — Verification and Release Gate

Stage 7 is a release gate, not another autonomous feature. It separates evidence from assumptions and prevents a green CI run from being described as proof that Orion works on a real phone.

## Required gates

### A. Source and automated checks
- [ ] Verify the tested commit SHA is the exact pull-request head.
- [ ] Android unit tests pass, including Stage 3 memory nudges, Stage 4 scheduler, Stage 5 probe/compose, and Stage 6 subagents.
- [ ] Bundled Python policy tests pass.
- [ ] Debug APK builds successfully.
- [ ] APK package/application ID, ARM64 ABI, and signing certificate are validated by CI.
- [ ] No test is skipped or silently converted to success to hide a failure.
- [ ] Review the complete workflow log for warnings or skipped verification steps.

### B. Device acceptance — Redmi A3 Pro
Use the APK artifact produced by the same successful workflow run. Install it over the existing app only when Android reports the signing key is compatible. If Android reports a signature conflict, stop and investigate; do not tell the user to uninstall and lose app data as a first response.

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
