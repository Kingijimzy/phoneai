# PhoneAI UI, Browser Automation, Skills, and Character Status

## UI polish in this pass
- The control-room settings screen uses tighter outer padding, reduced card spacing, and slightly more compact button styling for a denser mobile layout.
- Existing provider, voice, memory, routines, device tools, and integrations remain in their existing sections.
- The UI is native Android Views rather than a web-based design system; a future visual redesign should preserve accessibility, readable touch targets, and low-end-device performance.

## Skill Studio
The Skill Studio now has reviewed starter templates for:
- \`web_research\`: public web search and bounded read-only page research.
- \`browser_operator\`: visible-screen navigation, element checks, and waiting for page state.
- \`device_brief\`: time, battery, and device information.
- \`crypto_snapshot\`: public Bybit spot ticker only.
- \`memory_review\`: recall and optionally save relevant context.

Generated skills are declarative manifests, not downloaded scripts. They are created disabled, have a bounded tool allowlist and run budget, and must be reviewed before enabling. The browser operator is marked as requiring approval and its tool allowlist is checked before each skill tool call. Existing voice-learned routines remain available separately for replaying recorded actions.

## Browser capabilities and boundaries
- \`research_web\` reads public HTML/text with URL checks, redirect revalidation, timeouts, response limits, and untrusted-page handling.
- Browser/device control can use the Android accessibility service to read visible UI, click visible text, scroll, wait for elements, and operate bounded visual steps.
- This is not a general-purpose desktop browser driver. Login, payment, purchases, sending, posting, deleting, permission grants, account/security changes, and ambiguous confirmations must stop for user involvement.
- Browser and screen automation must be tested on the actual device and target apps; website layouts and Android accessibility trees vary.

## Human cinematic character
The shipped avatar is still a stylized Android Canvas drawing. It is **not** a photorealistic 3D human. The current improvements include expression/state animation and power/thermal-aware frame pacing.

A real cinematic 3D character still requires a legally redistributable rigged GLB/glTF or VRM model, a compatible Android 3D renderer, facial rig/blendshapes, and viseme/phoneme timing for accurate lip sync. No model asset or 3D renderer is bundled in this pass, so the app must not claim that photorealistic 3D has shipped. The current 2D avatar remains the safe fallback until a suitable asset and renderer are integrated and measured on the Redmi device.

## Verification
- GitHub Actions build success confirms source compilation and artifact upload only.
- It does not prove real-browser workflows, remote MCP compatibility, Telegram polling, model-service availability, device permissions, thermal behavior, or 3D rendering.
- Batch device acceptance should cover memory/tasks, voice, skill template creation and disabled-by-default behavior, one public research task, one low-risk browser navigation task, and stop/approval behavior before any consequential action.

## Companion UI refresh
- The setup screen now opens on a character-led dashboard with direct chat, character, skills, memory, and browser shortcuts.
- The floating chat panel has a framed character/status header, clearer bounded message bubbles, and a more conversational input hint.
- The current character renderer is still 2D Canvas. These UI changes do not turn it into a rigged 3D human; that requires a model asset and renderer integration.
