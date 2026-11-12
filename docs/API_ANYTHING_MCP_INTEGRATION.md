# API Anything integration

PhoneAI manages API Anything inside Orion's existing Alpine/PRoot runtime and connects it to the existing Streamable HTTP MCP client. This is a managed runtime integration, not a second MCP implementation.

## In-app setup

Open **Orion Settings → Integrations → Remote MCP → API Anything / Website Capability Engine** and choose **Install / start / repair API Anything**.

The installer:

1. Reuses Orion's pinned Alpine ARM64 runtime.
2. Installs Alpine Node.js, npm and Chromium, then verifies Node.js is at least 22.13.0.
3. Installs the pinned `api-anything@0.4.0` release.
4. Runs `api-anything setup --local-only --no-share`, so setup does not send searches, learned operations, repair reports or verification traffic to the shared catalog.
5. Starts `api-anything serve` on `127.0.0.1:8787` with the MCP endpoint at `/mcp`.
6. Reads the generated bearer token inside the guest, stores it through the Android Keystore-backed MCP credential path, pairs only this Orion-launched loopback server, and verifies both health and MCP tool discovery.

The server process is owned by Orion's app process and is restarted on the next app-process start when the managed integration is enabled. Use the settings screen to start/repair it or stop it. Stopping the server preserves API Anything's learned site operations in the Alpine home directory.

## Capabilities exposed to Orion

API Anything's own MCP server supplies site discovery, operation discovery, reads, batch reads, jobs/results, site status and `learn_site`. API Anything stores learned operation specs and can replay/repair drifted operations according to its own proof and replay rules. Orion consumes those operations through its existing MCP tool registry and executor; it does not duplicate the website learner or repair engine.

The server is started with `--allow-writes` so its write tool is available when the owner has enabled Orion's MCP write scope. API Anything's own write safeguards still apply. Orion requires a warning and explicit confirmation only for delete/wipe operations; ordinary requested website writes execute directly when the MCP write scope and service authentication are available. An enabled scope is not proof that a remote write was applied: inspect the tool result and report its actual outcome.

## Runtime and device constraints

- Requires Orion's Alpine/PRoot runtime, network access, Node.js >=22.13 and Chromium for learning/browser fallback. A first install can be large and take several minutes on mobile data.
- Shared, already-learned plain-HTTP operations can work without Chromium, but learning new operations and browser-tier fallbacks need it.
- The server listens only on loopback. No LAN or public listener is opened.
- Calls run from the device's network and local API Anything home. With `--local-only --no-share`, Orion does not opt into the upstream shared catalog.
- Android may terminate the app process under memory pressure. Orion attempts to restart the server on the next application-process start; this is not an OS-guaranteed always-on daemon.
- The current MCP connector stores one active endpoint. The managed integration refuses to silently replace a different configured remote MCP endpoint. Disconnect that endpoint first if you want to switch to the managed API Anything server.

## Manual fallback

If you prefer to manage the server yourself in Orion's Alpine shell, use:

```sh
node --version
npx -y api-anything@0.4.0 setup --local-only --no-share
npx -y api-anything@0.4.0 serve --host 127.0.0.1 --port 8787 --allow-writes
```

The first server start creates `~/.api-anything/serve-token`. Do not paste it into chat or commit it. Configure the endpoint as `http://127.0.0.1:8787/mcp` and store the token in Orion's MCP settings.

## GitHub account access

API Anything does not grant GitHub OAuth permissions. Public repository operations can use public endpoints; private/account-wide GitHub operations still require the dedicated GitHub connector with appropriate account permissions.

## Verification boundary

Source integration and Android CI do not prove a physical-device install. The final acceptance test must run on the POCO F3: install from the APK, start the managed service, verify health and tool discovery, learn a test read-only operation on a permitted site, call it twice to confirm persistence, simulate an unavailable server and verify restart/recovery, and confirm a write tool is blocked when the Orion write scope is disabled.
