# PhoneAI integrated acceptance checklist

This checklist is the current completion gate for the combined personal-agent build. Passing a GitHub compile is necessary but does not replace testing on the target Redmi Android phone.

## Implemented in the integrated branch
- [x] Four animated avatar looks (Orion, Nova, Atlas, Lumen).
- [x] Four persistent conversational personality profiles, independent of avatar look.
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

## Latest chat-first UX work (source changes pushed; build/device verification pending)
- [x] Keep normal chat requests in the conversation panel instead of moving every request into the floating status strip.
- [x] Render the user's message immediately before the AI response, so the chat has visible conversational flow.
- [x] Add long-press copy for user and AI message bubbles.
- [x] Make the animated character tappable to start voice input.
- [x] Add conversation starter chips for research, explanations, planning, summaries, writing, and deeper analysis.
- [x] Show a live animated thinking indicator in the chat while Orion generates a response.
- [x] Add an in-chat Stop control while a request is running; clean up the indicator when a run ends.
- [x] Add a dedicated cinematic 3D avatar stage with a rigged CC0 GLB, cinematic lighting, ambient scene, and a low-power 30 fps render cap.
- [x] Add a chat shortcut to open the 3D stage and keep the lightweight Canvas avatar as fallback.
- [x] Synchronize the 3D scene with listening/thinking/speaking/acting/approval state and TTS word/range callbacks.
- [x] Refresh Orion's launcher copy and dark visual palette.
- [x] Ease 2D avatar gaze/head transitions and the orb's pulse/orbit-speed changes.
- [x] Animate chat bubble entrance, panel opening, button/chip press feedback, and the thinking indicator.
- [x] Add 3D idle-clip preference when available, state-aware torso/shoulder/hip motion, subtle camera/light drift, and compatible blink/mouth morph-target blending.
- [ ] Verify the 3D stage loads the GLB from the approved CDN, the character framing/face cues look correct, and failure returns cleanly to the Canvas avatar.
- [ ] Verify head/eye/mouth movement follows voice states and TTS range callbacks without excessive lag or distracting motion, including whether this GLB exposes compatible facial morph targets.
- [ ] Check the 3D scene at normal and power-save/thermal conditions on the Redmi; confirm 30 fps cap, heat, memory, and background cleanup.
- [ ] Confirm the thinking indicator appears/disappears correctly and Stop cancels a slow request without leaving stale UI on the physical Android device.
- [ ] Confirm chat bubbles, IME/send behavior, copy action, and tap-to-talk on the physical Android device.
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
