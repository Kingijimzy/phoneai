# Stage 6 — Integrations and Safeguards

## Scope and status

Stage 6 begins with a deliberately small, read-only integration. This keeps service access isolated from the screen-control agent and avoids asking for personal account credentials before there is a secure OAuth design.

### Implemented in this pass: public GitHub lookup

The agent can call `github_public` with:
- `owner`: public GitHub account or organization name
- `repo`: repository name
- `section`: `repo`, `release`, or `issues`

The tool reads only fixed `https://api.github.com` endpoints using GET. It validates owner/repository components, applies connection/read timeouts, limits response bytes, sends no access token, filters pull requests out of the issue list, and performs no writes. Public repositories only; private repositories and authenticated account operations are intentionally unsupported. The returned repository content is data, not instructions for Orion.

Declarative skills may use this tool only when explicitly allowlisted in their manifest; the existing skill registry still enforces disabled-by-default, call/time budgets, and approval policy.

## Integration sequence

1. **Public GitHub — implemented, CI pending:** repository metadata, latest release, open issues. Verify the latest APK build before considering the implementation compile-verified.
2. **Integration permissions and status — next:** show each connector's scope, connection state, and whether it is read-only or can change data. Avoid implying a connector is connected when only a browser link exists.
3. **YouTube — partial existing capability:** existing tool opens YouTube search in the user's app/browser. Search metadata through an official API would require a user-managed key; don't scrape private account data.
4. **Gmail — planned:** use Android/browser OAuth or a trusted backend OAuth flow with least-privilege scopes. Never ask the user to paste passwords or put OAuth client secrets in the APK. Begin with read-only search; draft/send requires a separate approval step.
5. **Market data — planned:** public/read-only prices first, with provider/source/time shown. Keep Bybit/forex keys user-configured and never embedded in source or the APK. Trading analysis and alerts only; no automatic order placement.
6. **Credential and action audit — planned:** review key storage, redaction, URL allowlists, timeouts, rate limits, cancellation, and explicit approval for any consequential action.

## Acceptance criteria

- [ ] GitHub public repository metadata succeeds for a known public repository.
- [ ] Latest-release and open-issue lookups handle empty data, 404, rate limiting, and offline errors without crashing.
- [ ] Malformed owner/repository inputs are rejected; no arbitrary host can be selected.
- [ ] No GitHub token or other secret is transmitted or stored by this tool.
- [ ] A declarative skill can call `github_public` only if its tool allowlist includes it and all existing budgets remain enforced.
- [ ] The latest GitHub Actions build succeeds.
- [ ] Gmail OAuth, private GitHub access, and market-data APIs are not described as implemented until their own secure flows are built and tested.

## Limits

This is not GitHub account management: it cannot read private repositories, create issues, push code, change settings, or access private user data. It does not connect Gmail. The existing YouTube action opens search results but does not provide official API-backed video metadata. The app has not yet been tested on the user's phone for this stage.


## Telegram Bot API — implementation added; device verification pending

PhoneAI now includes a narrowly scoped Telegram Bot API connection:
- The user enters a BotFather token and one destination chat ID in the app settings screen. The token is encrypted using an AES-GCM key held by Android Keystore; the AI prompt and action arguments never contain the token.
- The bot identity can be checked with Telegram's getMe endpoint.
- telegram_send sends plain text only to the saved destination. The agent must ask for a fresh yes/no confirmation immediately before every send; the model cannot choose another recipient or URL.
- Network calls use the fixed HTTPS Telegram API host, short timeouts, bounded responses, and no redirects. Disconnect clears the stored token ciphertext and destination.
- This does not implement incoming Telegram updates, webhook hosting, group polling, inline commands, or a remote phone-control tunnel. Those require a separately secured always-on endpoint or a carefully designed foreground service and must not be implied by this outbound integration.

## MCP and workflow integrations

The app exposes an integration-status report and keeps remote MCP servers unconfigured by default. MCP is a protocol, not a single service: each server requires an explicit endpoint, authentication model, tool allowlist, timeout/call budget, and approval rules. Do not accept arbitrary server URLs or auto-enable every tool returned by a server. Next candidates after verification are a constrained remote-MCP client, official YouTube metadata, a least-privilege Gmail OAuth flow, and public/read-only market data. Telegram messages, email, posts, orders, and other external writes must have per-action approval.


## Remote MCP — endpoint setup and read-only discovery added

PhoneAI can save a user-selected MCP Streamable HTTP endpoint and optional bearer token, encrypted with Android Keystore, then perform initialize and tools/list discovery. Endpoints must use HTTPS except loopback HTTP for local development. Tool descriptions are untrusted data. Remote tool execution is enabled only after the agent displays the advertised catalogue, endpoint host, tool name, and arguments and obtains explicit per-call approval. The client re-queries the server's catalogue before each invocation. This is not a guarantee that a remote tool is safe; use only servers you trust. Some servers may require different protocol versions, session handling, OAuth, or transport behavior and may not work yet. Never send private screen contents, credentials, or secrets to remote MCP servers.


## Stage 6 improvements — public workflow visibility and read-only Telegram inbox

- GitHub public lookup includes a `runs` section for the latest five public Actions runs when GitHub permits unauthenticated access.
- Telegram has a manual one-shot inbox check for a saved numeric chat ID. It reads up to ten pending updates, stores an update offset, and does not execute incoming text as commands or act on behalf of the sender. Channel usernames remain outbound-only for this feature.
- Inbox polling is manual, not an always-running background service. This avoids an unattended remote-control channel and avoids promising reliable delivery while Android may stop the app.
- Device verification is pending. Public Actions API access may be restricted by GitHub; authenticated workflow control is not added here.

## Public crypto market data

PhoneAI can query Bybit public spot tickers for symbols such as BTCUSDT and ETHUSDT without a key. The method is read-only and cannot submit orders. It reports last price and basic 24-hour fields; it is not a signal, does not provide derivatives funding/open interest, and does not replace the Orion trading-analysis stack. FX feeds remain unconnected. API/network availability and symbol support require live verification.

## Live Soul watcher — event-driven observation

The primary Soul watcher is now attached to the already-enabled Android AccessibilityService event stream:
- It performs an initial local observation when the service connects and reacts to meaningful foreground-window changes.
- Rapid window churn is coalesced by a 30-second cooldown; observations run on the service worker executor, not on Android's accessibility callback thread.
- Live observation requires both the owner-controlled `orion_core` and `proactive` packs and a connected AccessibilityService. Disabling either pack stops new live observations.
- WorkManager remains as a durable recovery / scheduled-standing-order fallback after process death or reboot; it is not the primary live observer.
- The observer does not continuously capture screenshots, record audio, or inspect every text/content event. It responds to meaningful window transitions and reads only the local capability state already available to the accessibility service.
- Android or HyperOS can still disable/restrict the AccessibilityService, force-stop the app, or defer fallback work. Orion reports the service connection state and resumes observation when Android reconnects it; uninterrupted operation cannot be guaranteed by an app.
- The watcher remains bounded and policy-gated: it does not grant permissions, enable packs, or bypass approval for consequential actions.


## Stage 4–7 integration stack update — 2026-11-02

This Stage 6 PR is now based on `orion/navigation-feature-directory` (PR #120), which stacks the owner-control/security, graphite UI and feature-navigation work from PRs #118–#120 on the shared Central Brain base (PR #117).

- PR #157 head remains `c468d2ea`; its prior Android and PRoot CI runs validate that original head in isolation.
- GitHub currently reports this retargeted PR as mergeable, but the new base composition requires a fresh exact-head build before acceptance.
- PR #158 remains stacked on this branch and therefore inherits the #118–#120 UI/control chain.
- Stage 4 repair/Python (#132 → #145 → #154 → #155 → #156) remains a separate chain and must be reconciled before a unified release candidate is declared.
- Preserve owner approval, capability grants, privacy boundaries and task cancellation. Stage 5 remains on hold.
