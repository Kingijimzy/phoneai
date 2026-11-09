# Ultron agency adoption in Orion

## Bot behaviors removed / reduced

1. **Sticky mission** (`OrionMissionLock`) — "continue" resumes the user goal, not soul-watch/trading.
2. **Soft objective graph** (`updateStatusSoft` + resolveDispatchId) — unknown ids like `apkvision_size` no longer throw.
3. **Forced recovery** (`OrionForcedRecovery`) — sports/scrape failures suggest alternate tools instead of only explaining.
4. **Agency bootstrap** (`OrionAgencyBootstrap`) — every turn gets mission + recovery rules in context.

## Wire into Agent

At start of `runInternal`:

```kotlin
val turn = OrionAgencyBootstrap.begin(svc, command, recentTurns)
// use turn.effectiveGoal and turn.systemExtra in the planner prompt
```

After tool failure:

```kotlin
OrionAgencyBootstrap.afterTool(svc, goal, tool, result, attempt)?.let { ui.note(it) }
```

## Branch

`fix/ultron-agency-core` — merge to main after CI green.
