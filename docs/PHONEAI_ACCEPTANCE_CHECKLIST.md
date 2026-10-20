# PhoneAI acceptance checklist

This checklist distinguishes source changes from device-verified behavior. A successful GitHub Actions build proves the project compiles; it does not prove that every screen is stable on the Redmi device.

## Current app architecture

- [x] Native Home / Chat / Tools / Settings navigation.
- [x] Full-screen `MainChatActivity` for normal conversations, starter prompts, voice input, message history for the active service session, and a dedicated composer.
- [x] Separate floating `OrbView` command console for quick phone commands while another app is open.
- [x] New native in-app browser with address/search bar, back/forward/reload, HTTPS/HTTP-only top-level navigation, and a WebView that the accessibility agent can inspect and interact with.
- [x] Floating command console includes quick screen/battery/network checks, voice command, and a route into full chat.
- [x] `SettingsActivity` groups access/permissions, AI providers and models, voice/response style, memory/routines/skills, and integrations.
- [x] The character and network-loaded 3D activity have been removed from the active UI and manifest.
- [x] Native energy core responds to idle/listening/thinking/speaking/acting/waiting-for-approval modes with different colours, arcs, glow, waveform, and speech pulses.
- [x] Full-screen chat shows an in-context Stop control while Orion is busy; it calls the existing service stop path and animates its state feedback.
- [x] Agent notes are rendered as compact, subdued Live Activity cards instead of being silently dropped from the full-screen chat.
- [x] Home and chat use lightweight gradient surfaces, staggered entrances, message transitions, and touch-scale feedback without adding a heavy animation dependency.
- [x] Existing memory, tasks, routines, provider selection, public research, local skills, GitHub/Telegram/MCP helpers, Bybit public spot lookup, Network Radar, Game Sentinel, and SAF-limited file tools remain in the branch.
- [x] Actions with meaningful consequences remain permission-gated; no automatic trade execution was added.

## Stability and behavior checks

- [x] Agent tasks checkpoint their original request and bounded tool results in a local file so a step cap, network failure, or user stop can preserve progress.
- [x] The agent accepts “continue” / “resume” and passes the recorded results back into planning, with explicit instructions not to repeat successful actions.
- [x] Before executing a tool, the agent checkpoints the in-flight action; if the app dies mid-action, resume explicitly treats the outcome as uncertain and inspects state before repeating it.
- [ ] Device-test force-stop during a harmless navigation action and confirm resume inspects current screen before continuing.
- [ ] Verify checkpoint recovery after force-stopping the app, resuming after an AI provider failure, and ensuring completed actions are not repeated. This needs device testing.

- [ ] Resolve the user's reported force-close/internal-error issue with a physical-device reproduction and logcat evidence.
- [ ] Tap every Home, Chat, Tools, and Settings entry on the Redmi; verify each destination opens and returns without a crash.
- [ ] Verify full-screen chat can send a typed prompt and receive an answer without showing the floating panel over it.
- [ ] Verify the mic requests permission when needed and routes recognized speech into the full-screen conversation.
- [ ] Verify leaving full-screen chat restores the floating orb, and returning to chat does not duplicate transcript messages.
- [ ] Verify the floating console remains compact, supports quick commands, and opens full chat correctly.
- [ ] Verify AI provider setup, voice settings, memory editing, routines, skills, Telegram/MCP configuration, and device utilities each open without a crash.
- [ ] Verify keyboard resizing, scrolling, message selection/copy, and button feedback.
- [ ] Verify the chat Stop control cancels an active harmless task, stops voice playback, and returns the status/orb to idle without leaving the UI stuck.
- [ ] Verify live activity cards appear during phone tasks, remain readable on the Redmi screen, and do not overwhelm the conversation.
- [ ] Verify the orb changes colours and motion for all agent states without excessive heat or battery use.
- [ ] Verify stopping a task clears indicators and does not leave the UI stuck in a running state.
- [ ] Confirm accessibility/microphone denial produces a useful message rather than an activity crash.
- [ ] Confirm GitHub Actions builds the latest branch head and uploads a debug APK artifact.

## Safe undo roadmap feature

- [x] Added a bounded, app-private undo journal for supported saved-memory changes.
- [x] “Remember” records the prior snapshot when updating an existing matching memory; a new memory can be removed by undo.
- [x] “Update memory” stores before/after snapshots; undo only applies when the current memory still matches the recorded after-state.
- [x] “Forget memory” can be reversed by restoring the deleted memory with its original ID, only if that ID and text have not been reused.
- [x] Added `undo_last` and `undo_status` agent tools; instructions explicitly limit undo to saved-memory changes.
- [ ] Device-test add → undo, update → undo, forget → undo, app restart persistence, and stale-state conflict protection.
- [ ] Verify undo history remains usable after malformed/corrupt local journal data and storage write failures.
- [ ] Expand undo only to additional actions that have safe, well-defined inverse operations; never promise reversal of sent messages or other irreversible actions.

## Existing safety and service limits

- Public webpage research is read-only and uses URL/redirect checks, timeouts, response limits, source URLs, and untrusted-page handling.
- Local skills are declarative, disabled by default, tool-allowlisted, and bounded by call/time budgets; they do not run downloaded code.
- File operations remain scoped to user-selected Android Storage Access Framework URIs.
- API quota saver, provider fallback limits, and model retry budgets remain in place.
- Bybit lookup is public spot market data only; it does not place orders.
- A cloud compile cannot validate API keys, microphone availability, Android accessibility settings, or all third-party integrations.

## Known limitation

The force-close report is not yet reproduced on the physical phone in this workflow. Do not label the app fully stable until the device checks above pass. If the new build still closes, capture the crash stack from Android logcat so the exact exception can be fixed instead of guessing.

## Live-data and free-quota integration pass

Latest reliability pass: chat and agent system prompts explicitly prohibit claiming live access unless a live tool succeeded in the current turn. Public web-search responses include a retrieval timestamp, and failures remain visible instead of being replaced with guessed results. This is source-level behavior; network/device verification is still required.

Implemented in the integration branch (build/device verification still required):
- [x] Open-Meteo geocoding + current weather/today forecast; no key and no LLM call.
- [x] GDELT public news lookup for recent headlines; no key and no LLM call.
- [x] Direct DuckDuckGo HTML search for explicit web-search requests; no key and no LLM call. This public HTML interface can change or rate-limit requests, so failures must be reported honestly.
- [x] Direct Bybit public spot ticker for common crypto price questions; no key and no LLM call.
- [x] Quota-saver mode avoids extra model-list API requests, does not fan out across several model candidates after a timeout, and does not retry empty model responses.
- [x] Agent instructions prefer direct-data/local tools and batch up to four independent low-risk tool calls to reduce model turns.
- [ ] Build the latest branch head with GitHub Actions and inspect Gradle output.
- [ ] On the Redmi device, verify weather for a named city, news lookup, explicit web search, BTC price, offline/network failure messaging, and no crash.
- [ ] Measure actual provider requests for a multi-step task and verify primary + at most one configured backup under quota-saver mode.
- [ ] Verify search/news source links open and remain clearly labelled as external, untrusted content.
- [ ] Verify `open_url` launches Orion Browser (not the external browser), and that `read_screen`, `click_text`, scrolling, and typing work on ordinary accessible pages.
- [ ] Verify redirects work for Open-Meteo and GDELT on the device; test city names with country qualifiers and service/network errors.

### Data-source boundaries
- Open-Meteo is used for weather forecasts, not a guarantee of ground-station observations or perfect hyperlocal accuracy.
- GDELT is a public news index; coverage, freshness, ranking, and regional relevance can vary.
- DuckDuckGo HTML results are parsed from a public search page, not a stable official search API. If the page changes or blocks requests, PhoneAI should show a failure message rather than invent results.
- Bybit endpoint is public spot market data only. This is not an account connection, derivatives feed, trading signal, or order execution.
- Free model tiers still impose provider-owned rate limits. PhoneAI can reduce avoidable calls and resume work, but cannot guarantee unlimited inference.

### Provider and quota research notes (reviewed 2026-10-19)
- GitHub Models was retired on 2026-07-30, so its old inference provider was removed from the selectable provider list. This is separate from the read-only public GitHub repository integration.
- OpenRouter's free-model catalog changes frequently and still has rate limits; the fallback list is refreshed with currently listed free model IDs, but availability must be verified by live requests.
- Hugging Face's documented free Inference Providers allowance is currently only $0.10/month for free accounts, subject to change; do not describe it as unlimited free inference.
- Gemini, Groq, and other providers enforce their own model/project/account-specific request and token quotas. PhoneAI's per-minute caps are local safety throttles, not a claim about each provider's actual limit. When a provider exposes quota headers, PhoneAI now shows those alongside the local throttle.
- Open-Meteo's no-key free API is for non-commercial use and requires attribution. A commercial or ad-supported PhoneAI release must use a properly licensed weather source or an eligible paid Open-Meteo plan.
- Free endpoints can have changing availability and data/privacy terms. Do not send passwords, tokens, verification codes, or sensitive screen contents to public/free model endpoints.
