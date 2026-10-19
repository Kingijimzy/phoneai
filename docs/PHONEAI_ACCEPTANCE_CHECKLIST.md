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
- [x] Network Radar for current transport/capabilities, VPN/metered/captive-portal state, DNS, and MTU.
- [x] Game Sentinel for user-started sessions, battery/memory/thermal indicators, and local session history.
- [x] File operations limited to user-selected Android Storage Access Framework URIs.
- [x] GitHub Actions workflow can build a debug APK and retain an artifact.

## Required verification before calling the project complete
- [ ] Run GitHub Actions assembleDebug on the latest integrated head after thermal-aware avatar frame pacing changes; confirm the artifact matches the latest commit.
- [ ] Install the APK on the Redmi phone and test app startup, avatar selection, personality persistence, voice, memory, tasks, reminders, and provider switching.
- [ ] Test browser research on a public page and verify local/private addresses and unsafe redirects are rejected.
- [ ] Register a skill, confirm it starts disabled, enable it, run it explicitly, and confirm disallowed tools, call budgets, time budgets, and approval prompts are enforced.
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
- Avatar rendering is currently stylized 2D Canvas, not photorealistic 3D. See [Cinematic Avatar Roadmap](CINEMATIC_AVATAR_ROADMAP.md) for the renderer/model/lip-sync work still required.
- Trading functionality remains analysis/alerts only; no automatic trade execution is included.
