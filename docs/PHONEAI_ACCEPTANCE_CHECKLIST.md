# PhoneAI acceptance checklist

This checklist distinguishes source changes from device-verified behavior. A successful GitHub Actions build proves the project compiles; it does not prove that every screen is stable on the Redmi device.

## Current app architecture

- [x] Native Home / Chat / Tools / Settings navigation.
- [x] Full-screen `MainChatActivity` for normal conversations, starter prompts, voice input, message history for the active service session, and a dedicated composer.
- [x] Separate floating `OrbView` command console for quick phone commands while another app is open.
- [x] Floating command console includes quick screen/battery/network checks, voice command, and a route into full chat.
- [x] `SettingsActivity` groups access/permissions, AI providers and models, voice/response style, memory/routines/skills, and integrations.
- [x] The character and network-loaded 3D activity have been removed from the active UI and manifest.
- [x] Native energy core responds to idle/listening/thinking/speaking/acting/waiting-for-approval modes with different colours, arcs, glow, waveform, and speech pulses.
- [x] Existing memory, tasks, routines, provider selection, public research, local skills, GitHub/Telegram/MCP helpers, Bybit public spot lookup, Network Radar, Game Sentinel, and SAF-limited file tools remain in the branch.
- [x] Actions with meaningful consequences remain permission-gated; no automatic trade execution was added.

## Stability and behavior checks

- [ ] Resolve the user's reported force-close/internal-error issue with a physical-device reproduction and logcat evidence.
- [ ] Tap every Home, Chat, Tools, and Settings entry on the Redmi; verify each destination opens and returns without a crash.
- [ ] Verify full-screen chat can send a typed prompt and receive an answer without showing the floating panel over it.
- [ ] Verify the mic requests permission when needed and routes recognized speech into the full-screen conversation.
- [ ] Verify leaving full-screen chat restores the floating orb, and returning to chat does not duplicate transcript messages.
- [ ] Verify the floating console remains compact, supports quick commands, and opens full chat correctly.
- [ ] Verify AI provider setup, voice settings, memory editing, routines, skills, Telegram/MCP configuration, and device utilities each open without a crash.
- [ ] Verify keyboard resizing, scrolling, message selection/copy, and button feedback.
- [ ] Verify the orb changes colours and motion for all agent states without excessive heat or battery use.
- [ ] Verify stopping a task clears indicators and does not leave the UI stuck in a running state.
- [ ] Confirm accessibility/microphone denial produces a useful message rather than an activity crash.
- [ ] Confirm GitHub Actions builds the latest branch head and uploads a debug APK artifact.

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

### Data-source boundaries
- Open-Meteo is used for weather forecasts, not a guarantee of ground-station observations or perfect hyperlocal accuracy.
- GDELT is a public news index; coverage, freshness, ranking, and regional relevance can vary.
- DuckDuckGo HTML results are parsed from a public search page, not a stable official search API. If the page changes or blocks requests, PhoneAI should show a failure message rather than invent results.
- Bybit endpoint is public spot market data only. This is not an account connection, derivatives feed, trading signal, or order execution.
- Free model tiers still impose provider-owned rate limits. PhoneAI can reduce avoidable calls and resume work, but cannot guarantee unlimited inference.
