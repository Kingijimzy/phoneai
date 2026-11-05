# Orion reset — central brain finish

## Central brain on all paths

| Path | Wiring |
|------|--------|
| Command / `runInternal` | `OrionAgentBrainBridge.planTurn` before FastIntents & model |
| Ambiguous "continue" | `blockTools` → clarify, no tool dispatch |
| Direct vs multi-step | Brain notes + structuredTask soft-override when direct preferred |
| Model context | `lastBrainSystemExtra` appended to CentralBrain context |
| Every tool activity note | `OrionAgentBrainBridge.afterTool` verify + auto memory on accept |
| Fast intents (music/search) | Still after brain gate (clarify can block) |

## Modules

- `OrionBrainPipeline`
- `OrionAgentBrainBridge`
- `OrionToolOutcomeVerifier`
- `OrionMemoryLifecycle`
- `OrionPreActionContext`
- `OrionContextualIntentInterpreter` (references)

## Prove

1. Wait for **wire-brain-into-agent** + **Build APK** green on this branch.  
2. Install APK → run eval scenarios 2, 4, 7, 9, 15.  
3. Device ticks remain required for Rule 10.

## Not claimed

Full removal of every historical planner class (still present as helpers). Authority is the brain bridge on the live command path.
