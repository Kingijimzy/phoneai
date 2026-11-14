# Codex on Alpine PRoot: runtime acceptance plan

## Decision under test

Orion already deploys the official ARM64 musl Codex App Server inside its pinned Alpine PRoot runtime. It downloads the release archive on Android, verifies the pinned SHA-256, extracts the server and companion executables in the guest, and launches:

```sh
codex-app-server --listen stdio://
```

The Android app supplies `CODEX_HOME=/android-app/codex-session`, backed by the app-private `filesDir/codex-session` directory (not the evictable cache directory). Do **not** install Node.js/npm or a second Codex CLI unless this acceptance test demonstrates a concrete limitation in the native App Server package.

## Important isolation boundary

PRoot is a userspace compatibility layer, not a kernel security sandbox. It does not prevent Android from killing the owning process, and it does not provide VM/container-grade isolation. The guest can access explicitly mounted host paths. Codex currently receives a broad guest execution policy; only use this configuration with trusted workspaces and reviewed Orion tool permissions. Do not describe PRoot as protection against malicious code.

## Device under test

- Device: POCO F3, Android 13, ARM64 (record actual build and available storage at test time).
- Guest: pinned Alpine manifest; current installer smoke test expects Alpine 3.22.7 and `aarch64`.
- Codex package: pinned by `CodexRuntimeInstaller.VERSION` and `SHA256`.
- App build: record APK SHA-256, package version, Git commit, and signing certificate.

## Acceptance sequence

Run on a physical device with a connected ChatGPT account. Capture timestamped Orion logs and the exact app/server versions for every step.

| ID | Test | Pass condition |
|---|---|---|
| PRT-01 | Fresh runtime install | Download completes; SHA-256 matches; all three expected executables are installed and executable. |
| PRT-02 | Idempotent install | A second install reuses the valid installation; it does not redownload or damage the guest. |
| PRT-03 | Guest architecture | Guest reports `aarch64`; Alpine release matches the pinned manifest; app-server starts and completes initialize. |
| PRT-04 | Account persistence | Complete ChatGPT device login; close Orion normally; encrypted Android Keystore credential is retained; plaintext `auth.json` is absent after vault persistence. |
| PRT-05 | Thread continuity | Ask Codex to remember a unique test token; close the app-server process; start a new process; resume the same thread and ask for the token. It must return the exact token without replaying the old transcript. |
| PRT-06 | App process death | Force-stop Orion, relaunch, and resume the same thread. Record whether Codex history and authentication survive. |
| PRT-07 | Tool round trip | Invoke one harmless registered read-only Orion tool. Verify exact tool name/arguments, one dispatch, result returned to Codex, and an evidence receipt in Orion. |
| PRT-08 | Invalid tool arguments | Send malformed arguments in a controlled test. Verify validation rejects them and no capability is executed. |
| PRT-09 | Long-running turn | Run a bounded task with visible progress. Verify streamed events, no UI-thread blocking, and correct terminal status. |
| PRT-10 | Interrupt and resume | Interrupt an active turn; verify the exact active turn is cancelled and the thread remains resumable. |
| PRT-11 | Background survival | Start a harmless long-running task, background Orion, lock the screen, and observe process/service state. Record Android/OEM termination; do not assume PRoot prevents it. |
| PRT-12 | Context growth | Continue at least 20 turns; verify Orion sends only the latest user message to a resumed native thread and does not reconstruct the complete transcript on every turn. |
| PRT-13 | Model/tool contract change | Change model or dynamic tool schema; verify a new thread is created and the previous thread remains intact. |
| PRT-14 | Recovery after server crash | Kill only the Codex server process; verify the next request reports/restarts cleanly and does not claim the interrupted turn succeeded. |

## Required evidence

For each test record: PASS/FAIL/BLOCKED, UTC timestamp, device/API level, app commit, APK SHA-256, Codex version, relevant log excerpt, thread ID (redact account identifiers), and observed behavior. A source-level unit test is not a substitute for physical-device acceptance.

## Go/no-go criteria

- **GO for PRoot deployment:** PRT-01 through PRT-10 and PRT-12 through PRT-14 pass; PRT-11 is characterized with an explicit Android process-lifecycle strategy. No credential plaintext remains after vault persistence, and invalid tool arguments never dispatch.
- **NO-GO:** checksum mismatch, wrong ABI, lost native thread history, leaked plaintext credentials, duplicate tool dispatch, false success after interruption, or unrecoverable server crash.
- **Do not compare against npm yet:** only evaluate npm Codex CLI if the official App Server package lacks a required capability or fails a reproducible test. If evaluated, install it inside the existing Alpine guest with `apk add nodejs npm`, not `apt`; compare the same acceptance sequence and keep only one supported runtime.

## Current known implementation boundaries

- The Android app owns the child process and stdio pipes. If Android kills the app process, the server process may also die; PRoot does not change that ownership.
- Codex thread IDs/model/tool fingerprints are persisted by the account provider, while Codex thread data lives under the persistent Codex home.
- The App Server client has resume, compaction request, and interrupt protocol support. Automatic compaction policy and physical interruption/recovery behavior still require verification.
- The current Codex config uses broad guest execution permissions. Treat this as a trust boundary, not as a security sandbox.
- No physical-device result is implied by this plan. Mark each row only after collecting the evidence above.
