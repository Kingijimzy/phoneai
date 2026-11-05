# Chat = only truth UI

## Rule

The chat stream is the sole user-facing source of truth for what Orion is doing.
Cards and animations must reflect **backend notes**, never fabricated progress.

## Structured events

| Backend note | Card |
|--------------|------|
| `[CONTEXT]` | CONTEXT |
| `[BRAIN]` | BRAIN |
| `[TASK:PLANNED]` / PLAN | PLAN |
| `[TASK:RUNNING]` | RUNNING |
| `[TASK:RESULT]` / SHELL / BROWSER RESULT | RESULT |
| `*:FAILED]` | FAILED |
| `[VERIFY:REJECT]` | VERIFY |
| `[TASK:CLARIFY]` | CLARIFY |
| `[TASK:APPROVAL]` | APPROVAL |

Plain assistant text stays a normal bubble.

## Components

- `OrionChatTruthUi` — parse + card builder +n- `ChatSteps` / `StepTracker` — step strip from the same notes  
- `Thinking` — only while waiting on model; replaced by real reply  

## Device check

1. Send a command; confirm CONTEXT / BRAIN cards appear before tools.  
2. Force a bad music route; confirm VERIFY card if rejected.  
3. Confirm no “success” animation without a RESULT note.
