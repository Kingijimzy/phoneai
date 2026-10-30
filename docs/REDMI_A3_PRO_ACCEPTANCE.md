# Redmi A3 Pro Physical Acceptance — Orion Stage 7

This checklist separates automated evidence from behavior that must be proven on a real device.
Do not mark a row PASS from a source-code inspection or an emulator-only run.

## Target device profile

- Device: Redmi A3 Pro
- Android / HyperOS: record the exact version shown in Settings
- RAM/storage: record the available memory and free storage before testing
- APK: install the debug APK produced by the exact PR head being tested
- Build identity: record the Git commit SHA and GitHub Actions run URL

## Automated evidence required before device testing

- [ ] Exact-head GitHub Actions run is completed with conclusion `success`.
- [ ] `gradle testDebugUnitTest assembleDebug --no-daemon` passes.
- [ ] Python sandbox policy tests pass (`python3 -m unittest discover -s app/src/test/python -v`).
- [ ] Extension sandbox tests pass (`ExtensionSandboxTest`, `SelfExtendingExtensionsTest`, and `CapabilityGapPlannerTest`).
- [ ] Runtime/restart tests pass (`OrionRuntimePersistenceTest`, `OrionRuntimePolicyTest`, `OrionRecoveryPolicyTest`).
- [ ] Permission/notification and per-tool override regressions pass (`OrionNotificationPolicyTest`, `ToolPermissionOverrideTest`).
- [ ] MCP retry-scope tests pass (`McpConnectorScopePolicyTest`): safe handshake/catalogue requests may retry once; `tools/call` must not retry.
- [ ] APK archive, required ARM64 native libraries, package metadata, and APK signature validation pass.

## Physical acceptance matrix

Record PASS, FAIL, or BLOCKED. Add a short observation for every FAIL/BLOCKED result.

| ID | Scenario | Acceptance evidence |
|---|---|---|
| D01 | Clean install | APK appears in Android installer; package installs without parsing/signature error |
| D02 | Cold launch | App opens from launcher; no immediate crash; chat screen usable |
| D03 | Process restart | Create a local queued task, force-stop/reopen app, confirm state is recovered without duplicate external action |
| D04 | Talk / Assist / Autopilot / Builder | Each mode is selectable and remains selected after app restart |
| D05 | Permissions already granted | Open autonomy/settings screens repeatedly; no redundant runtime permission prompt |
| D06 | Permission denied | Denied capability is reported accurately; Orion does not claim it can use the capability |
| D07 | Approval boundary | A protected action pauses for approval; deny means no action is executed |
| D08 | Chat continuity | Send a message, switch provider, return to conversation; shared conversation context remains coherent |
| D09 | Provider fallback | Simulate or observe a transient primary-provider failure; bounded fallback occurs; auth/billing failures do not cause endless rotation |
| D10 | MCP reconnect | Disconnect network during initialize/list; reconnect and confirm bounded safe retry; verify tool calls are not duplicated |
| D11 | Extension draft and validation | Draft a harmless JSON-to-JSON extension; run valid, invalid, and boundary test cases; activation remains approval-gated |
| D12 | Background notification | Complete/fail a standing-order check; verify configured notification preference and Android notification permission are respected |
| D13 | Battery/background behavior | Test with screen off and battery saver on/off; record whether the watcher is paused or resumed by Android/HyperOS; verify WorkManager leaves queued work intact and asks to reopen Orion when the accessibility service is unavailable |
| D14 | Memory and learning | Complete a multi-step harmless task; confirm only a route candidate is recorded and it is labelled unverified until reviewed |
| D15 | Thermal and memory stability | Use chat + one tool flow for 15 minutes; record crashes, app restarts, excessive heat, or OS process kills |

## Safe test data

Use a harmless local task such as: "Open Settings, read the displayed Android version, and report it."
Do not use real banking, wallet, trading, login, messaging, payment, or account-changing actions as acceptance tests.
Use a deliberately invalid extension fixture that has no external side effects.

## Evidence capture

For each test session record:

- Date/time and tester
- Device model, Android version, HyperOS version
- App version / APK filename
- Exact commit SHA and Actions run URL
- Test IDs with PASS / FAIL / BLOCKED
- Screenshots for visible UI outcomes
- Crash details from Android bug report or filtered logcat, with personal data and tokens removed

Useful Termux / ADB commands when ADB is available:

```sh
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb logcat -c
adb logcat -d -t 3000 | grep -iE 'FATAL EXCEPTION|AndroidRuntime|PhoneAI|Orion'
```

If ADB is unavailable, use Android's built-in bug report / app crash dialog and attach screenshots. Never paste API keys, tokens, phone numbers, private messages, or account data into test evidence.

## Release gate

A capability is:

- **Source-present** when implementation exists.
- **Unit-tested** when its automated regression tests pass.
- **CI-verified** when the exact commit's required workflow passes.
- **Device-verified** only when the corresponding Redmi scenario passes with evidence.
- **Reliable** only after repeated device runs, including restart and background/battery conditions, show no unresolved failure.

A green CI run does not equal physical-device acceptance. WorkManager provides a durable, deferrable recovery signal only: it may wake an already-connected accessibility service, or notify the user to reopen Orion. It does not start the accessibility service, run screen actions, or replay uncertain standing-order effects. Background scheduling remains subject to Android and HyperOS process/battery policies; a test must record actual behavior rather than promise uninterrupted execution.
