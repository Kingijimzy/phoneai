# Cinematic Avatar Roadmap

## Current implementation (honest status)

PhoneAI currently draws its four character looks with Android Canvas. Nova has layered skin/hair shading, eye gaze, blinking, and a speech-driven mouth envelope; the orb and face show listening, thinking, speaking, acting, and approval states. This is a stylized 2D renderer, not a true 3D or photorealistic human model.

## What was improved in this pass

- Both `AvatarView` and `OrbView` now adapt redraw pacing to Android's power and thermal state.
- Normal state targets roughly 30 fps; power-save or moderate thermal pressure targets about 15 fps; severe thermal pressure targets about 8 fps.
- The policy is local, uses Android's exposed PowerManager status, adds no permission or network request, and leaves the interaction states intact.
- This reduces avoidable rendering work on entry-level devices such as 4 GB RAM phones while retaining motion.

## Next route: more realistic expressions and offline reliability

1. **Upgrade the model.** The current CC0 model is a stylized superhero. The next visual milestone is a clothed, higher-detail character with documented redistribution rights and a facial rig or blendshapes; do not bundle a nude base mesh as Orion's default appearance.
2. **Keep the renderer isolated.** The first 3D stage uses Three.js in a restricted Android WebView and keeps Canvas for the floating button and fallback. Evaluate native Filament only if device testing shows the WebView renderer is too heavy or unstable.
3. **Make the model available offline.** The current GLB is fetched from a pinned public CDN revision. Bundle a reviewed, compressed asset only after confirming size, license, and APK impact.
4. **Improve expression mapping.** Current cues include idle head motion, state-based head/neck movement, blinking, and a synthetic mouth cue; a better facial rig should support blink, brow, smile, jaw, and eye gaze through real bones or morph targets.
5. **Improve lip sync.** Current motion follows Android TTS range callbacks and a smoothed mouth cue, not true phoneme/viseme timing. Add viseme timing only when a reliable, low-cost signal is available; never imply exact lip sync from word callbacks alone.
6. **Measure before optimizing.** Verify load time, frame pacing, memory, temperature, battery behavior, screen rotation, pause/resume, and fallback on the Redmi device. Keep the scene capped near 30 fps and stop rendering when the activity is not visible.

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

- No photorealistic human model or advanced facial blendshape rig is bundled; the current CC0 superhero is stylized.
- The GLB and Three.js modules are fetched at runtime; the 3D stage is not yet available offline.
- Speech animation is callback-driven and approximate, not phoneme-perfect.
- GitHub Actions compilation and physical-device rendering/voice/thermal behavior must be verified before calling this stage complete.

## 3D acceptance checklist

- [ ] Confirm the latest GitHub Actions run builds the current branch head and uploads the APK artifact.
- [ ] Confirm the model URL loads in Android WebView on mobile data and Wi-Fi; check that the import map and GLTF loader work.
- [ ] Check face placement and model framing; ensure the facial overlay does not float away from the head when it turns.
- [ ] Confirm listening/thinking/speaking/acting/approval states update while the stage is open.
- [ ] Confirm Android TTS range callbacks produce visible but restrained mouth movement.
- [ ] Confirm closing the stage releases WebView resources and restores the lightweight floating avatar.
- [ ] Check power-save, warm-device, and normal operation on the Redmi; record measured behavior rather than assuming performance.
