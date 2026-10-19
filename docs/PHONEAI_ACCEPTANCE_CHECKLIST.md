# PhoneAI integrated acceptance checklist

This checklist is the current completion gate for the combined personal-agent build. Passing a GitHub compile is necessary but does not replace testing on the target Redmi Android phone.

## Implemented in the integrated branch
- [x] Native Jarvis-inspired animated energy core replaces character visuals in the active app UI.
- [x] Persistent conversational response profiles remain available in Settings.
- [x] Existing local memory, tasks, routines, voice, and provider selection preserved.
- [x] Plain chat retains a small context window; phone-agent loop bounded to 16 turns and batched tools to four calls.
- [x] Default-on API quota saver keeps the selected provider first, limits fallback to at most one backup provider, limits model retries, and counts actual generation requests against local per-minute budgets.
- [x] Read-only public webpage research with URL/redirect checks, timeouts, response limits, source URL, and untrusted-page instructions.
- [x] Local declarative skills: disabled by default, allowlisted tools, per-run call/time budgets, and approval gates for consequential supported actions.
- [x] Skill Studio starter templates for public research, guarded browser navigation, device brief, public Bybit spot snapshot, and memory review; generated skills start disabled.
- [x] Network Radar for current transport/capabilities, VPN/metered/captive-portal state, DNS, and MTU.
- [x] Game Sentinel for user-started sessions, battery/memory/thermal indicators, and local session history.
- [x] File operations limited to user-selected Android Storage Access Framework URIs.
- [x] GitHub Actions workflow can build a debug APK and retain an artifact.

## Chat-first redesign and stability work (source changes pushed; physical-device verification pending)
- [x] Keep normal chat requests in the conversation panel instead of moving every request into the floating status strip.
- [x] Render the user's message immediately before the AI response, so the chat has visible conversational flow.
- [x] Add long-press copy for user and AI message bubbles.
- [x] Replace the character in the floating command control with a lightweight mode-reactive energy orb.
- [x] Add conversation starter chips for research, explanations, planning, summaries, writing, and deeper analysis.
- [x] Show a live animated thinking indicator in the chat while Orion generates a response.
- [x] Add an in-chat Stop control while a request is running; clean up the indicator when a run ends.
- [x] Add a dedicated full-screen chat activity separate from the floating command panel.
- [x] Sync the energy-core animation to idle/listening/thinking/speaking/acting/approval modes.
- [x] Remove the 3D character activity from the app manifest and normal UI flow to reduce WebView/CDN startup risk.
- [x] Refresh Orion's launcher copy and dark visual palette.
- [x] Ease 2D avatar gaze/head transitions and the orb's pulse/orbit-speed changes.
- [x] Animate chat bubble entrance, panel opening, button/chip press feedback, and the thinking indicator.
- [x] Add 3D idle-clip preference when available, state-aware torso/shoulder/hip motion, subtle camera/light drift, and compatible blink/mouth morph-target blending.
- [ ] Reproduce and resolve the reported force-close/internal-error issue on the physical Android device using logcat.
- [ ] Tap-test every home, chat, tools, and settings action on the Redmi; confirm no activity crashes and no dead-end settings buttons.
- [ ] Verify full-screen chat send/receive, voice callbacks, activity pause/resume, and return to the floating orb.
- [ ] Confirm the thinking indicator appears/disappears correctly and Stop cancels a slow request without leaving stale UI on the physical Android device.
- [ ] Confirm message rendering, keyboard/send behavior, and copy action on the physical Android device.
- [ ] Verify latest branch head compiles; an in-progress Actions run alone is not a pass.

## Required verification before calling the project complete
- [ ] Stage 6 public GitHub integration: verify repo metadata, release and issue lookups; invalid input, 404/rate limit, and offline handling. See [Stage 6 integrations and safeguards](STAGE_6_INTEGRATIONS_AND_SAFEGUARDS.md).
- [ ] Run GitHub Actions assembleDebug on the latest integrated head after thermal-aware avatar frame pacing changes; confirm the artifact matches the latest commit.
- [ ] Install the APK on the Redmi phone and test app startup, avatar selection, personality persistence, voice, memory, tasks, reminders, and provider switching.
- [ ] Test browser research on a public page and verify local/private addresses and unsafe redirects are rejected.
- [ ] Create a Skill Studio template, confirm it starts disabled, review its allowlist, enable it, run it explicitly, and confirm disallowed tools, call budgets, time budgets, and approval prompts are enforced.
- [ ] Test Network Radar with Wi-Fi/mobile/VPN where available.
- [ ] Test Game Sentinel session start/end/history and confirm the displayed thermal/battery information matches Android's exposed values.
- [ ] Test file picker, text preview/read, save, copy, and delete confirmation using disposable test files.
- [ ] Verify chat API usage over several interactions with quota saver both on and off; local request counts are guardrails, not provider-side billing/token analytics.
- [ ] Test low-memory behavior, power-save/thermal-aware animation pacing, and background service stability on the actual phone.
- [ ] Confirm no API keys or other secrets are committed to the APK or repository.

## Known limits
- Browser support is read-only research, not unrestricted browser clicking or account automation.
- Game Sentinel does not claim measured FPS or GPU temperature and does not monitor foreground apps covertly.
- File operations only affect documents the user explicitly selects.
- Skills are declarative tool allowlists, not arbitrary downloaded code.
- Human-like profiles provide a consistent conversational style; they do not make the model human or guarantee emotional understanding.
- The new cinematic 3D stage uses a stylized CC0 superhero GLB in WebView; it is not photorealistic, is network-dependent, and has approximate callback-driven lip sync. The floating avatar remains 2D Canvas. See [Cinematic Avatar Roadmap](CINEMATIC_AVATAR_ROADMAP.md) for remaining realism, offline, and device-validation work.
- Trading functionality remains analysis/alerts only; no automatic trade execution is included.


## Native app shell and chat UI architecture pass (source pushed; device checks pending)

- The launcher is now a dedicated Home / Chat / Tools / Settings shell rather than exposing every configuration control on one long page.
- Settings were moved into a dedicated `SettingsActivity`; the original provider, voice, memory, skills, browser, recording, device utility, and integration controls are preserved there.
- Home emphasizes the animated avatar, chat entry, cinematic 3D entry, memory, and skills. Tools groups diagnostics, research/browser access, recording, and agent capabilities.
- Navigation transitions use a short fade/slide; touch surfaces use restrained scale feedback. The layout remains native Android views to avoid adding a heavy UI framework.
- Chat styling now uses a quieter assistant reading surface and separate user bubbles, with clearer header hierarchy and a rounded composer inspired by modern AI chat products. This is an inspiration, not a pixel-for-pixel copy of Grok.
- The current chat still opens as an accessibility overlay so it can operate over other apps. The new launcher shell does not replace the underlying agent or tool execution architecture.
- Physical device verification remains required for IME resizing, tap targets, scroll behavior, accessibility overlays, and thermal performance.
