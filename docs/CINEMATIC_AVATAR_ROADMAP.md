# Orion visual system roadmap

## Current direction

The character/3D-avatar approach has been removed from the app's active UI. Orion now uses a native, lightweight Jarvis-inspired animated energy core: layered glow, orbiting arcs, a bright central sphere, speaking waveform, and an orbiting work indicator.

The core changes colour and motion with the agent state:
- **Idle:** calm cyan pulse and slow orbit.
- **Listening:** green/teal glow and expanding rings that react to microphone level.
- **Thinking:** violet core and faster scanning arcs.
- **Speaking:** cyan waveform and speech-callback energy pulses.
- **Acting:** amber accents and a moving orbit marker.
- **Waiting for approval:** amber rings with a slower, deliberate pulse.

The native renderer is intentional for now: it avoids a network-loaded 3D scene, removes WebView/CDN startup from the normal app flow, and is more suitable for a lower-memory Android phone. The separate floating command orb is compact; the full-screen chat uses a larger core and its own message composer.

## Current UI architecture

- `MainActivity`: home dashboard and bottom navigation for Home, Chat, Tools, and Settings.
- `MainChatActivity`: full-screen conversational experience with animated core, starter prompts, message history for the active service session, and a full-width composer.
- `PhoneAiService`: floating orb and compact command panel for quick commands while another app is in front.
- `SettingsActivity`: provider/model setup, voice and response style, memory, routines, skills, permissions, and integrations.
- `DeviceUtilitiesActivity`: Network Radar, Game Sentinel, and device diagnostics.
- `OrbView`: native, state-aware animation shared by the home surface, full-screen chat, and floating overlay.

## Stability and performance principles

1. Keep the main UI native and avoid network-loaded visual assets during launch.
2. Keep animations state-driven and reduce frame pacing under power-save or thermal pressure.
3. Do not start a second floating chat when the full-screen chat is active.
4. Keep main-chat messages inside `MainChatActivity`; keep the floating icon's command panel compact and independent.
5. Build and run the Android unit/compile checks after each structural change. A successful cloud build does not prove the UI is stable on a physical device.
6. The user-reported force-close issue remains a physical-device acceptance gate; reproduce it with logcat before calling the app stable.

## Future optional exploration

A more advanced visual renderer can be considered later only if it offers a clear benefit over the native core and passes offline, memory, thermal, accessibility, and lifecycle tests. A character model is not part of the current design.
