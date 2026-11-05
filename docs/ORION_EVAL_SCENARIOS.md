# Orion evaluation scenarios (reset Rule 10)

Minimum **20** realistic scenarios. Mark each with evidence (unit test name, CI run, or device screenshot). Source presence alone is not a pass.

| # | Scenario | Expected | Automated | Device |
|---|----------|----------|-----------|--------|
| 1 | Fresh chat greeting | Coherent reply, no tools | partial | |
| 2 | Open music app | Native app, not web YouTube | unit: OrionBrainPipelineTest | |
| 3 | Play song on Spotify | play_media / Spotify path | | |
| 4 | "continue" with active task | Resumes objective | unit: continuePhrase | |
| 5 | "continue" with no history | Asks clarification | unit: ambiguousContinue | |
| 6 | "fix what you just broke" | CORRECT_PREVIOUS frame | | |
| 7 | Multi-step ordered task | Graph or sequential steps; no hard-stop | | |
| 8 | Invalid model graph | Local fallback + continue | | |
| 9 | Tool error then recover | Alternate approach or clear failure | | |
| 10 | Irrelevant tool result rejected | Verifier reject + retry | unit: musicOpenWeb | |
| 11 | Long conversation (20+ turns) | Context retained on key facts | | |
| 12 | Interrupted task resume | Checkpoint restored | | |
| 13 | Cancel in-flight task | State cancelled, no silent continue | | |
| 14 | Provider timeout / fail | Fallback model or honest error | | |
| 15 | Memory: state a preference once | Later turn uses it without re-ask | | |
| 16 | Memory correction | User corrects fact; old value not reused | | |
| 17 | Phone battery / device info | Direct tool, verified answer | | |
| 18 | Web research + summary | Sources grounded; no fabricated success | | |
| 19 | Permission-denied tool | Clear explanation; no fake success | | |
| 20 | Chat UI matches backend | No fabricated progress cards | | |

## CI evidence log

| Date | Head SHA | Build APK | Notes |
|------|----------|-----------|-------|
| 2026-11-04 | 8a0c4af | [run 41793317167](https://github.com/Kingijimzy/phoneai/actions/runs/41793317167) success | dispatch fix |

## Device acceptance

_Not yet filled. Install PhoneAI-apk from the green run and tick scenarios 2, 4, 7, 9, 15._
