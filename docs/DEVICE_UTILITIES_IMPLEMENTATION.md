# PhoneAI Device Utilities: Network Radar, Game Sentinel, File Handling

This change is intentionally additive. It provides native Kotlin utility modules and leaves MainActivity, Agent, Tools, and PhoneAiService unchanged to avoid conflict with parallel agent work.

## Network Radar

File: `app/src/main/java/com/phoneai/app/NetworkRadar.kt`

Call `NetworkRadar.inspect(context)` on a background executor. It reports active transport, whether Android sees Internet capability, whether Internet is validated, captive-portal status, metered status, VPN transport, a short DNS server list, and MTU. It needs only `ACCESS_NETWORK_STATE`.

Privacy/scope: no Wi-Fi SSID/password collection, no packet capture, no device discovery, no port scanning, and no claims about measured bandwidth or ping. A future UI can add a user-triggered connectivity test with explicit destination allowlisting.

## Game Sentinel

File: `app/src/main/java/com/phoneai/app/GameSentinel.kt`

- `deviceSnapshot(context)`: battery percentage/temperature when exposed by Android, available/total memory, Android thermal status when available, and a conservative recommendation.
- `startSession(context, appLabel, packageName)`: starts one user-initiated session; returns null if invalid or another session is active.
- `endSession(context)`: closes the active session and saves up to 100 completed sessions.
- `activeSession(context)` and `history(context)`: retrieve local records.

Privacy/scope: does not monitor foreground apps, request Usage Access, draw an overlay, modify game files, change developer settings, or promise FPS/GPU-temperature measurements. Recommendations are advisory; Android may not expose battery temperature on every device.

## File handling

File: `app/src/main/java/com/phoneai/app/PhoneFileHandler.kt`

Use Android Storage Access Framework UI (for example `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT`) to get user-selected content URIs, then call:
- `inspect(context, uri)`
- `readText(context, uri)` (UTF-8, max 1 MB)
- `writeText(context, uri, text)` (max 1 MB)
- `copy(context, source, destination)` (max 25 MB)
- `deleteSelected(context, uri)` (must be preceded by a clear user confirmation)

No broad storage permission or raw path access is used. UI integration should explain the operation and show the selected filename before writing, copying, or deleting. Do not use these methods to silently read user documents.

## Build and verification

The workflow now runs `gradle assembleDebug` for pull requests and uploads the APK as an artifact. It does not publish a GitHub release for a pull-request build. A merge to main or manual workflow dispatch retains the existing release behavior.

These modules are not yet wired into the app's main UI or the AI agent tool schema. Integration should be a separate change: provide user-visible screens/buttons, run blocking file/network operations off the main thread, catch errors, and only expose an action to the agent with explicit permission/confirmation where appropriate.

## Acceptance checks for follow-up integration

- Network Radar: offline, Wi-Fi/mobile, captive portal, VPN, metered/unmetered states; no private network scan.
- Game Sentinel: start/end, reject duplicate active sessions, history persistence, memory/battery unavailable cases.
- File Handling: empty file, oversized file, revoked URI permission, read-only destination, provider failure, copy limit, and explicit delete confirmation.
- Run the pull-request GitHub Actions build and review its actual result before merging.
