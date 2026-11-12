# Orion voice identity

## Default voice

Orion's default spoken voice is **Kokoro-82M `bf_emma`**, a British-English female neural voice. The alternate British female preset is `bf_isabella`. The selected speaker is shared by standard chat/voice and Gemini Live.

The app uses the quantized English v0.19 model through the existing Sherpa-ONNX Android runtime. INT8 is the mobile-balanced profile: it keeps the model outside the APK and limits download, storage and CPU cost while preserving Kokoro's speaker identities.

## Install

1. Open Orion **Settings → Voice & speech**.
2. Leave **Use Orion's British neural voice (Kokoro)** enabled.
3. Select **Install Kokoro British neural voice**.
4. Wait for download, SHA-256 verification, extraction and activation to finish.
5. Use **Test spoken voice** to preview Emma, or choose Isabella and preview it.

The model is downloaded only after the owner explicitly selects Install. It is stored in Orion's private app files and is not bundled in the APK.

Upstream asset: [kokoro-int8-en-v0_19.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2)

- Published archive size: 103,248,205 bytes.
- Pinned SHA-256: `c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd`.
- The installer also preserves the upstream `LICENSE` file in the installed model directory.
- The installer bounds download/extraction sizes, rejects checksum mismatches, checks required model/voice/token/pronunciation files, supports cancellation and activates the verified model through a staged directory.

## Runtime behaviour

- `bf_emma` maps to Kokoro speaker ID 7; `bf_isabella` maps to speaker ID 8 in the v0.19 English speaker table.
- Sherpa-ONNX is configured for `en-gb` phonemization and three CPU threads.
- The existing sentence-streamed PCM playback path is retained. Real generated PCM RMS drives the living orb; no timer-based fake speech animation is used.
- `OrionSpeechTextPolicy` removes source/code blocks, raw JSON/XML, URLs, Markdown syntax and internal execution traces from speech while retaining user-facing prose, warnings and table values.
- Gemini Live requests text output and routes it through Orion's selected local voice. Its microphone audio still streams to Google under the existing per-session quota/privacy warning; spoken output is generated locally by Orion.
- If Kokoro is not installed or cannot start, Android TTS is the fallback. The existing offline-only preference still prevents network TTS fallback.

## Device acceptance

On the POCO F3 (Android 13, ARM64), verify:

1. Emma and Isabella previews sound British and do not read code, JSON, URLs or internal tool logs.
2. Standard chat and standard live call use the same selected voice.
3. Gemini Live uses that same local voice and can be interrupted by the user.
4. The orb follows actual voice amplitude, shows listening/thinking/speaking states, and returns to idle/listening cleanly.
5. Run a 5–10 minute voice session and check latency, heat, battery use and audio dropouts.

CI validates compilation, unit tests, APK package/signature and ARM64 runtime. It does not replace the physical audio/performance checks above.
