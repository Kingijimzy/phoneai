# PhoneAI JARVIS 2.0 — implementation roadmap

This roadmap records the agreed direction for PhoneAI. It is a plan, not a claim that every feature is already implemented.

## Product goal

Build a practical Android personal assistant that can talk, understand the current screen when permission and a capable vision model are available, remember user-approved context, manage tasks that persist across restarts, and use permitted Android actions. Keep the existing Kotlin app and GitHub Actions cloud build as the foundation.

## Current foundation confirmed in the repository

- Native Kotlin Android application.
- Selectable AI providers and models for chat and vision.
- Voice output, hands-free/wake-word options, and accessibility-driven phone actions.
- Persistent memory store with UI to inspect, edit, and delete memories.
- Screen vision and screen recording workflows.
- GitHub Actions builds a debug APK and publishes it as a GitHub Release.
- First persistent task store and agent tools were added in build 8.

## Milestones

### 1. Reliable task management (in progress)
- Keep tasks on-device and available after app/process restarts.
- Show tasks in the app UI, including status, priority, and optional deadline.
- Support create, update, complete, cancel, and delete.
- Next: implement Android notifications and reboot-safe scheduling for due tasks. Use supported Android scheduling APIs and explain battery/permission restrictions. A saved deadline alone is not a notification.

### 2. Memory that the user controls
- Reuse the existing MemoryStore instead of creating a second competing memory database.
- Retrieve relevant memories before planning or answering.
- Save durable preferences, project context, decisions, and explicit reminders to remember.
- Let the user inspect, edit, and delete stored memories.
- Never save API keys, passwords, one-time verification codes, or sensitive screen contents by default.
- Consider local-first storage; explain clearly before adding cloud sync.

### 3. Avatar and voice
- Inspect the actual avatar_front_end source, scripts, environment requirements, license, and LiveKit flow before integration.
- Prefer a lightweight WebView-hosted avatar if the existing frontend requires web technologies; otherwise choose the simplest maintainable option.
- Connect avatar states to real app states: idle, listening, thinking, speaking, executing, waiting for approval, and error.
- Keep a usable native fallback when the avatar cannot load or the network is unavailable.
- Do not embed API secrets in the Android APK. Any server-side LiveKit token endpoint must keep secrets server-side.

### 4. Safe phone actions
- Use Android APIs and AccessibilityService only with user-granted permissions.
- Ask for confirmation before sending messages, deleting files/data, purchases/payments, changing security settings, or other consequential actions.
- Keep a visible stop/cancel path and a clear record of actions/results.
- Do not claim an action succeeded until the tool result confirms it.

### 5. Agent loop
- Understand the request → retrieve relevant memory → plan a small number of steps → choose a tool → execute → inspect the result → verify or ask for help.
- Resume saved tasks after app restart where possible, but never claim continuous background execution is guaranteed by Android.
- Bound retries and loops; make progress and failures visible to the user.

### 6. Performance and provider cost
- Keep the app responsive on entry-level Android phones.
- Avoid unnecessary animations, duplicate model calls, and large always-running background processes.
- Use only model capabilities actually supported by the selected provider/model.
- Make provider failures and rate limits understandable; do not describe paid quotas as free.

### 7. Verification and delivery
- Preserve existing working features and repository history.
- Add tests for task persistence, malformed storage, task status transitions, reminder scheduling, permission denial, and provider failures.
- Use GitHub Actions to compile the debug APK; report the exact run result and artifact/release link.
- Never claim tests passed unless they actually ran.

## Suggested implementation order

1. Task list/create/update UI.
2. Android deadline notifications and reboot-safe scheduling.
3. Verify memory retrieval and memory safety.
4. Inspect and integrate the avatar frontend with real voice/agent states.
5. Add resumable multi-step task execution and an action log.
6. Run cloud build and test on a real Android device.

## Constraints

- No auto-trading or other irreversible actions without explicit user confirmation.
- Do not invent environment variables, frontend endpoints, or capabilities; inspect the code first.
- Keep API keys and server secrets out of source control and APK resources.
- Android background execution, notifications, microphone access, and accessibility actions remain subject to OS permissions, battery optimization, and device policy.
