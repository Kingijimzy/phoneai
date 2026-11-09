# Orion vs Ultron — honest status (after agency merge)

## What is true now

- Mission lock, soft objective graph, forced recovery hints, and idle pulse policy are **in source on main** (PR #230 + pulse branch).
- Debug APK from that tip must still go green and be installed on device.

## What is not true

- Orion is **not** proven "better than Ultron" as a lived companion until device scenarios pass.
- Orion does **not** have "no limits." Android permissions, tool gates, and owner policy remain. Unlimited silent rm/SMS/APK install is not a product goal.
- Autonomous events run only when **idle** (no active MissionLock). They must not hijack `continue`.

## Ultron still wins until

1. Score / scrape / open-app recovery works on device without essays.
2. `continue` always resumes the same mission.
3. Idle pulse never injects trading mid-task.
4. Twenty scenarios ticked on a real phone.

## Open-source patterns adopted

- Closed tool loop (result → next decision).
- Soft failure of unknown plan tokens (no crash).
- Sticky goal ownership.
- Idle-only proactivity (not mid-turn background noise).
