# Orion reset — progress (finish pass)

## Completed in source (this branch)

| Deliverable | Status |
|-------------|--------|
| Architecture audit | `docs/ORION_INTELLIGENCE_RESET_AUDIT.md` |
| Target architecture | same doc |
| Root-cause | same doc |
| Backlog | same doc |
| Brain pipeline contract | `OrionBrainPipeline` |
| Tool outcome verifier | `OrionToolOutcomeVerifier` |
| Auto memory lifecycle | `OrionMemoryLifecycle` |
| Agent bridge | `OrionAgentBrainBridge` |
| Agent wire-up | CI workflow `wire-brain-into-agent` injects into `Agent.kt` |
| Eval scenarios (20) | `docs/ORION_EVAL_SCENARIOS.md` |
| Unit tests | Brain / memory / music verify |
| CI green APK | run 41793317167 @ 8a0c4af; newer runs after this commit |

## Not claimable without you

- Physical-device acceptance ticks on the 20 scenarios  
- Production quality of automatic memory on real chat weeks  

## Rules still in force

No new agents, trading desks, Soul frameworks, or dashboards until device eval is filled.

## Install

Latest successful **Build APK** on `orion/objective-graph-autonomy-fix` → Artifacts → PhoneAI-apk.
