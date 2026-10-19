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

## Stage 1 implementation (source pushed; build/device verification pending)

- Added `CinematicAvatarActivity`, a dedicated full-screen WebGL/Three.js scene with a rigged 3D character, cinematic cyan/violet rim lighting, floor rings, ambient particles, slow idle motion, and a 30 fps draw cap for lower-power phones.
- The model is the Quaternius Universal Base Characters superhero male GLB bundled by the public `programasweights/avatar` project. The source project's asset notes identify the model as CC0 1.0; the scene loads it from jsDelivr at a pinned upstream revision instead of committing a binary into this repository.
- The activity follows Orion's live listening/thinking/speaking/acting/approval states. Android TTS range callbacks pulse the scene mouth cue; the scene animates a jaw bone when the rig exposes one, and uses a small cyan facial overlay as a fallback for rigs without facial bones.
- Added a `3D Avatar` entry in the chat shortcut row. The existing Canvas avatar remains the compact/offline fallback.
- WebView JavaScript is limited to the local scene; top-level navigation is blocked, file/content access is disabled, and network requests are limited to the pinned jsDelivr host.

### Known limitations of this first 3D stage

- This is a real 3D skinned character, but the chosen CC0 model is a stylized superhero, not a photorealistic human.
- The model is fetched from the network and is not yet bundled/offline. If CDN access fails, the 3D stage shows an error and the lightweight Canvas avatar remains available after closing it.
- Speech sync is driven by Android TTS range callbacks and a smoothed mouth/jaw cue; it is not phoneme-perfect viseme sync.
- Build success and actual appearance, model bone alignment, voice timing, battery/thermal load, and fallback behavior still need verification on the Redmi Android device.

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
