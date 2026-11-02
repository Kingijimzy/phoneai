# Orion Python sandbox — Android deployment and operational boundaries

Updated: 2026-11-01

## Runtime contract

Orion bundles CPython 3.11 through Chaquopy. Direct `python_exec` scripts run in-process with a deliberately small AST-validated builtins/module surface and a typed `phone` facade. This is a capability-restricted interpreter, **not** a hostile-code OS container. Do not run arbitrary third-party packages or untrusted extensions with the direct phone facade.

### Available standard-library surface

- `json`: bounded `loads` / `dumps`.
- `datetime`: date/time primitives and approved conversion/formatting methods.
- `math`: approved numeric constants and functions.
- `re`: bounded input/pattern sizes and a basic nested-quantifier rejection.
- `asyncio`: bounded `run` / `sleep`; no subprocess, event-loop policy, task spawning, or arbitrary await syntax.
- `urllib.parse`: URL parsing and encoding only. `urllib.request`, raw sockets and arbitrary imports remain unavailable.

The interpreter bounds source size, AST nodes, loops, range length, output, sequence growth, integer size and exponentiation. Generated extensions continue to run without the `phone` facade.

### Scoped files

Python file operations are restricted to:

`filesDir/orion-python/workspace`

Limits: 2 MiB per file, 20 MiB total, 180-character relative paths, no traversal and no symlink traversal. Writes use a temporary file and atomic replacement where supported. The shell wrapper checks workspace integrity before running a command.

### Controlled system commands

The shell capability is an argv-only `ProcessBuilder` wrapper. It does not invoke `sh`, `su`, root, command substitution, pipes, redirection or arbitrary executable paths. Only a fixed read-only set is registered: `ls`, `cat`, `head`, `tail`, `grep`, `wc`, `date`, `uname`, `df`, and `pwd`. File operands must be workspace-relative. Commands have a four-second timeout and bounded output.

This is intentionally not a general shell. If a future capability needs a mutating command, add a typed app-owned adapter with explicit scope, preflight, evidence, approval policy, verification and rollback; do not add arbitrary command strings.

### HTTP and WebSocket network access

Network access requires both:
1. the owner-granted NETWORK scope in Sensor & Context Permissions; and
2. an exact host grant in **Manage approved Python hosts**.

Remote hosts require HTTPS/WSS. RFC1918 IPv4 addresses (10/8, 172.16/12, 192.168/16) and explicit loopback hosts may use HTTP/WS only when the owner enables the local plain-HTTP option for that exact host. Link-local, multicast, unspecified, mixed public/private DNS answers and unapproved hosts are rejected. DNS answers are pinned for each request/session; redirects are disabled for HTTP requests.

GET is read-only and does not prompt after the exact host grant. POST and WebSocket sends ask each time unless the owner separately enables the host's persistent write grant. Revoking a host closes access to its active WebSocket session on its next operation. WebSocket sessions are bounded, process-scoped and use a visible data-sync foreground notification. They do not survive process death.

A WebSocket is a transport, not a complete live-audio stack. Microphone capture remains in the existing owner-started voice flow and still requires Android microphone permission. Do not claim a sub-second end-to-end voice latency guarantee without physical-device measurements against the target endpoint.

## Android Accessibility bridge

The Python facade does not start or impersonate an AccessibilityService with an intent. Android binds `PhoneAiService` through the manifest's `BIND_ACCESSIBILITY_SERVICE` declaration and `accessibility_service_config.xml`. Python calls narrow methods on that already-bound service:

- `phone.accessibility_status()`: report system-enabled and service-connected state.
- `phone.accessibility_open_settings()`: owner-confirmed navigation to Android Accessibility settings.
- `phone.accessibility_screen()`: read the production screen snapshot only while SCREEN_CONTEXT consent is active and sensitive surfaces are excluded.
- `phone.ui_tap_text()`, `phone.ui_tap()`, `phone.ui_swipe()`: require SCREEN_CONTEXT consent, a connected service, safe screen checks, and per-action owner confirmation.

The service cannot be activated silently. The user must accept Orion's disclosure and enable Orion in Android Accessibility settings. The Android system remains the authority for binding, lifecycle and revocation.

## Xiaomi Redmi A3 Pro / HyperOS deployment

On the Redmi A3 Pro, labels vary by HyperOS release and region. Manually check:

1. Settings → Apps → Manage apps → Orion → enable **Autostart**, if offered.
2. Orion → Battery saver / Battery usage → **No restrictions** or allow background activity, if offered.
3. Settings → Apps → Permissions → allow notifications and microphone only when those features are used.
4. Enable Orion under Android Accessibility only after reading the in-app disclosure.
5. If using the overlay, grant display-over-other-apps / pop-up permission separately.
6. Lock Orion in Recents if the device exposes that option.
7. Keep sufficient free storage and avoid force-stopping Orion during a live session.

Orion now promotes long delegated specialist provider work to a visible WorkManager foreground execution with a data-sync notification. WebSocket sessions also hold a visible foreground service while open. Durable WorkManager jobs can be deferred and retried after eligible restarts; they are not real-time schedulers. No app can guarantee survival after force-stop, all OEM cleanup policies, battery exhaustion, Android foreground-service time limits or user revocation. The user-controlled HyperOS settings above are part of deployment, not something the app can enable on the owner's behalf.

## Physical-device acceptance checklist

Run these checks on the actual Redmi A3 Pro after installing the CI APK:

- Enable NETWORK consent; add a trusted Home Assistant host such as `192.168.x.x` with local HTTP enabled; verify HTTP GET.
- Confirm an unapproved neighboring LAN IP is blocked.
- Verify HTTPS GET to an explicitly approved remote API; verify remote HTTP is rejected.
- Open WSS to an approved service, receive text and binary frames, send a frame only after approval or a persistent write grant, revoke the host and confirm the session is denied/closed.
- Verify a Python workspace write/read survives app restart and cannot traverse `..` or symlinks.
- Confirm battery/location/notifications/contact reads do not cause duplicate prompts once their respective grants and Android permissions are active.
- Confirm accessibility status, screen read and owner-confirmed UI gesture; test a sensitive banking/wallet surface is blocked.
- Queue a specialist task, lock the screen, wait for the visible foreground notification and verify completion or a durable retry/review state.
- Repeat with battery restrictions enabled and disabled; record HyperOS version, app version, network type, screen-off duration, and whether the OS deferred or stopped work.

CI/Robolectric tests do not substitute for this physical-device matrix. Until it is recorded, Redmi A3 Pro deployment remains pending.
