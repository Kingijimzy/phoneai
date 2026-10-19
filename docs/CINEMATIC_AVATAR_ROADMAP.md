# Cinematic Avatar Roadmap

## Current implementation (honest status)

PhoneAI currently draws its four character looks with Android Canvas. Nova has layered skin/hair shading, eye gaze, blinking, and a speech-driven mouth envelope; the orb and face show listening, thinking, speaking, acting, and approval states. This is a stylized 2D renderer, not a true 3D or photorealistic human model.

## What was improved in this pass

- Both `AvatarView` and `OrbView` now adapt redraw pacing to Android's power and thermal state.
- Normal state targets roughly 30 fps; power-save or moderate thermal pressure targets about 15 fps; severe thermal pressure targets about 8 fps.
- The policy is local, uses Android's exposed PowerManager status, adds no permission or network request, and leaves the interaction states intact.
- This reduces avoidable rendering work on entry-level devices such as 4 GB RAM phones while retaining motion.

## Route to genuine cinematic 3D

1. **Choose a redistributable character asset.** Prefer a rigged GLB/glTF or VRM human model with documented license and redistribution rights. Do not ship a model scraped from a website or with unclear commercial/redistribution terms.
2. **Add a 3D renderer behind a feature boundary.** Evaluate Android Filament or a compatible glTF renderer in a separate branch; keep the current Canvas avatar as a fallback for unsupported devices or renderer failures. Confirm current Android/Gradle compatibility before pinning dependencies.
3. **Prepare a mobile-ready model.** Use a modest polygon budget, compressed textures, one or two material variants, and a small set of facial blendshapes (blink, jaw open, smile, brow). Test memory and GPU load on low-end hardware.
4. **Drive expressions from app state.** Map idle/listening/thinking/speaking/acting/waiting-for-approval to explicit animation parameters. Preserve the visible approval state and do not imply an action succeeded before the service reports it.
5. **Improve lip sync in two steps.** First use the speech engine's available utterance callbacks and a smoothed amplitude envelope. For real phoneme-level sync, add viseme/phoneme timing or audio analysis; the current word-pulse mouth movement is only an approximation.
6. **Add a safe fallback and performance gate.** Keep Canvas mode selectable, lower frame rate under thermal pressure, avoid rendering when detached/invisible, and measure frame pacing, memory, and battery during a combined test.

## Not yet implemented / verified

- No GLB/VRM human model is bundled.
- No Filament or other 3D renderer is integrated.
- No true 3D lighting, head rotation, skeletal facial rig, or phoneme-level lip sync exists yet.
- The adaptive frame pacing compiles only after the next Actions run; actual thermal response and animation quality still require device verification.

## Stage 5 follow-on checklist

- [ ] Check for compile errors in the new thermal/power-aware frame pacing.
- [ ] Compare animation at normal, battery-saver, and warm-device conditions on the Redmi phone.
- [ ] Confirm animation remains responsive after navigating away and returning.
- [ ] Measure whether the avatar meaningfully reduces heating/battery use; do not claim savings without measurement.
- [ ] Select and license a mobile-ready 3D character before implementing the renderer.
