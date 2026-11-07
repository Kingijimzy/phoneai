# Orion — Redmi A3 Pro acceptance run

This checklist is for the actual Android phone, not a desktop emulator.

## Target device assumptions

- Redmi A3 Pro, ARM64 / `aarch64`
- Android 16 / API 36
- 4 GB RAM
- App minimum SDK 30; current candidate targets SDK 34 and packages the ARM64 runtime

The app is Android-native Kotlin. The Linux/Alpine environment is launched inside Orion's Android app-private runtime; Termux is an optional bridge, not a prerequisite.

## First launch and HyperOS readiness

- [ ] Install the exact candidate APK and confirm the package updates/installs cleanly.
- [ ] Open Orion and complete first-run setup.
- [ ] Grant Android notification permission.
- [ ] Enable Orion's Accessibility service from Android Settings.
- [ ] In HyperOS app settings, allow Autostart/background activity and set battery use to Unrestricted (labels may vary by HyperOS version).
- [ ] Reopen Orion and confirm the watcher reports its real service state, not merely an enabled toggle.

Do not use Android's Force stop for background tests: Android suppresses scheduled work after a force-stop until the user launches the app again. Swipe Orion away from Recents for the process-death test.

## Runtime and Python

- [ ] Open Orion Terminal in Built-in / Alpine mode.
- [ ] Run `uname -m`; expected architecture: `aarch64`.
- [ ] Run `cat /etc/alpine-release`.
- [ ] Run `python3 --version`.
- [ ] Run `git --version` and `node --version` if those packages are installed in the selected runtime.
- [ ] In Orion Python, run `print(42)`; expected result: `42`.
- [ ] Create a small file in Orion's workspace, read it back, and confirm the returned receipt reports a verified read-back.
- [ ] Confirm Python UI actions route through Orion's normal capability dispatcher.
- [ ] Confirm HTTP POST and WebSocket sends are denied until the relevant host has an explicit write grant in Python network settings; after the grant, they run without a per-request confirmation dialog.

## Unified execution and Drone Swarm

- [ ] Start two independent specialist jobs from one parent objective.
- [ ] Confirm each job appears in the durable task/activity history with a real queued/running/completed state.
- [ ] Stop the parent objective.
- [ ] Confirm cancellation reaches both the legacy specialist queue and Drone Swarm tasks.
- [ ] Start a read-only research task, swipe Orion away from Recents, reopen it, and confirm WorkManager resumes or safely recovers it.
- [ ] Interrupt a coding task and confirm an ambiguous result is marked for owner review rather than blindly replayed.

## Cerebro memory

- [ ] Save a harmless owner-approved preference or project fact.
- [ ] Ask a related question in Talk mode and verify the relevant memory is retrieved.
- [ ] Ask a related question through Command mode and verify the same memory is retrieved.
- [ ] Restart Orion and repeat the query.
- [ ] Confirm private arguments, credentials, and raw tool payloads do not appear in action/evidence history.

## Prometheus extensions

- [ ] Create or load a harmless test extension.
- [ ] Run its exact-source sandbox test.
- [ ] Confirm the test receipt is bound to the source hash.
- [ ] Activate only after a passing receipt.
- [ ] Confirm the enabled version appears in history.
- [ ] Roll back to a prior version and confirm the rollback has its own passing test receipt.

## Chronos and background recovery

- [ ] Enable the owner-controlled proactive/Chronos settings and the required Android service permission.
- [ ] Create a harmless test schedule/standing order.
- [ ] Swipe Orion away from Recents and wait through the scheduled window.
- [ ] Confirm the run is recorded with its actual timestamp and outcome.
- [ ] Reboot the phone, launch Orion once, and confirm persisted schedules and queued work are restored.
- [ ] Confirm the app reports a deferred/missed run honestly if HyperOS delays WorkManager; it must not claim the job ran when it did not.

WorkManager is persistent but not an exact-alarm guarantee. HyperOS battery management can defer background work; this must be measured on the phone.

## Confirmation-policy matrix

Only these three actions may display the action-confirmation surface:

| Action | Expected |
|---|---|
| Restart | One confirmation |
| Shutdown | One confirmation |
| Toggle Wi-Fi | One confirmation |
| Ordinary shell/Python/file/browser actions | No action confirmation |
| Telegram/SMS/other sends with their capability and OS requirements satisfied | No action confirmation |
| Extension test, activation, history and rollback | No action confirmation |
| Workspace/task operations | No generic action confirmation |

Android permission grants, provider sign-in, MCP endpoint pairing, and saved host-write grants are setup/capability configuration—not per-action confirmation prompts. Destructive device-level shell primitives remain hard-blocked rather than being presented as confirmation dialogs.

## Report back

For any failed item, send the exact screen/error and the step number. Do not reinstall over the candidate before saving the first failure details; they help identify whether the issue is a Kotlin/runtime defect, Android permission, or HyperOS background restriction.
