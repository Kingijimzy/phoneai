# Orion Intelligence Reset — Architecture Audit

**Date:** 2026-11-05  
**Branch audited:** `orion/objective-graph-autonomy-fix`  
**Rule:** Freeze feature expansion. Prove and simplify the intelligence pipeline.

This document does **not** claim device-proven behaviour. Source presence ≠ working product.

---

## 1. Primary objective (reset)

One dependable central AI assistant that can:

1. Understand the user  
2. Maintain context  
3. Remember useful information  
4. Reason and select capabilities  
5. Execute and verify outcomes  

The user must not need to understand internal architecture.

---

## 2. Existing architecture (what exists in source)

### 2.1 Decision / conversation path

| Component | Role | Competing? |
|-----------|------|------------|
| `Agent.kt` (~230KB) | Main loop: plan → tools → results | **Primary** but overloaded |
| `Nim` (talk + command) | Model client | Two budgets; dual modes |
| `OrionCentralBrain` | Context pack assembly | Good direction; not sole owner of decisions |
| `OrionSoulEngine` / capability snapshots | Live capability picture | Overlaps CentralBrain |
| `OrionCommandIntentPolicy` | Hard routing for structured / phone actions | Competing with model tool choice |
| `OrionContextualIntentInterpreter` | “continue / that / previous” style intents | Partial |
| `FastIntents` | Deterministic play/search/open shortcuts | Good for latency; bypasses brain |
| `Local` / skill runners | Extra execution paths | Parallel planners |
| `OrionObjectiveGraph` | Multi-step graph | Useful; was over-mandatory |
| Task lifecycle / checkpoints | PLAN → CHECK → AUTHORIZE → EXECUTE | Sound idea; complex |

**Root issue:** Multiple planners and routers decide *what to do*. The model is not a single “central brain”; it is one of several decision sources.

### 2.2 Memory

| Store | Typical use |
|-------|-------------|
| Chat history / `ChatHistoryStore` | Session transcript |
| `MemoryStore` | Manual / tool memory rows |
| Route / experience stores | Prior procedures as hints |
| Objective graph + checkpoints | Task state |
| Companion / profile pieces | Character, not durable user facts |

**Root issue:** Memory is **fragmented**. Lifecycle “Observe → Extract → Store → Retrieve” is incomplete; much still depends on explicit tool writes or prompt stuffing, not automatic relevance retrieval.

### 2.3 Tools

| Layer | Notes |
|-------|-------|
| `Tools.kt` | Large surface of device/actions |
| `ToolRegistry` / packs / `AgentPolicy` | Permission gates |
| Capability registry / board | Enablement UI |
| Fast intents / media routing | Fixed mappings |

**Root issue:** Selection is a mix of model JSON tool calls, policy gates, and hard-coded intents. Validation of “did this result satisfy the user goal?” is weak.

### 2.4 Execution reliability

| Piece | Status in source |
|-------|------------------|
| Direct actions | Present |
| Multi-step graph | Present; simplified fallback added |
| Checkpoints | Present |
| Recovery / failed-call guard | Present |
| Always accurate task state in UI | Partial |

**Root issue:** Graph was too mandatory; hard-stops and invalid model graphs caused “zero objectives done.” Fallback paths were added but need device proof.

### 2.5 Recent intelligence-oriented fixes (this branch)

- Pre-action context note (`OrionPreActionContext` / `[CONTEXT]`)
- Native-first music open (`MediaPlayRouting` / FastIntents)
- Objective graph session + local synthesis + continue-after-plan-fail
- Restored `resolveDispatchId` (unit tests green)
- **Build APK success** on commit `8a0c4af` (run 41793317167)

These improve consistency at the edges. They do **not** complete the reset.

---

## 3. Root-cause summary

1. **No single authoritative brain** — Agent + policies + FastIntents + graph + skills all decide.  
2. **Context is assembled, not reasoned over** — large prompt packs; weak reference resolution.  
3. **Memory is not automatic** — stores exist; lifecycle and retrieval quality do not.  
4. **Tool choice is under-validated** — success string ≠ goal achieved.  
5. **Complexity without proof** — many modules; few end-to-end scenarios with device evidence.  
6. **UI / backend drift risk** — progress notes can outrun real state if not tied to receipts.

---

## 4. Simplified target architecture

```
User (Chat primary)
    │
    ▼
┌─────────────────────────────┐
│  Central Brain (one owner)  │
│  understand → context       │
│  memory retrieve → plan     │
│  select tools → execute     │
│  verify → respond / recover │
└─────────────┬───────────────┘
              │
     ┌────────┼────────┐
     ▼        ▼        ▼
  Memory   Capabilities  Execution engine
  (tiers)  (tools/packs) (simple | multi-step)
```

**Rules for simplification:**

- One conversation owner (Agent loop reduced to brain API + thin adapter).
- FastIntents remain as *capability shortcuts invoked by the brain*, not parallel brains.
- Objective graph is **optional** for multi-step only; simple tasks skip it.
- Memory writes from observed outcomes with provenance; retrieval by relevance.
- No new agents/dashboards until eval suite passes.

---

## 5. Prioritized backlog (smart + automatic, not bigger)

### P0 — Prove current path

1. Install green APK from successful CI; run 10 scripted device scenarios.  
2. Document pass/fail with screenshots (context, open music, continue task, tool failure recovery).  
3. Freeze all feature PRs that add packs/UI/agents.

### P1 — One brain surface

4. Define a single entry: `CentralBrain.handle(userMessage) → Response`.  
5. Route FastIntents / structured tasks **through** that entry (no silent side paths).  
6. Collapse duplicate “what should I do?” policies into ranked intent + tool policy used only by the brain.

### P2 — Context intelligence

7. Relevance retrieval for conversation + memory + task (not full dump).  
8. Explicit reference resolver: “that”, “continue”, “previous task”, “fix what you broke”.  
9. Always attach last tool receipts to the next plan step.

### P3 — Automatic memory

10. Observe assistant/tool outcomes → candidate facts.  
11. Deduplicate + confidence + user correction/delete.  
12. Separate short-term window vs long-term vs task state.

### P4 — Tool selection & verification

13. Tool cards with schema + when-to-use in brain prompt only for **enabled** tools.  
14. Goal-check after each tool: accept / reject / alternate.  
15. Never mark task complete without verification evidence.

### P5 — Execution engine

16. Default path: direct action. Multi-step only when needed.  
17. Checkpoint + resume + cancel always visible in chat.  
18. Recovery: retry once with alternate tool; then ask user.

### P6 — Eval suite (≥20 scenarios)

19. Automated unit/integration where possible; device checklist for the rest.  
20. CI gate: no “capability works” without test name + last green run id.

---

## 6. Capability scorecard (honest)

| Capability | Source | Device-proven |
|------------|--------|---------------|
| Chat round-trip | Yes | Partial |
| Context pack before plan | Yes | Partial |
| Automatic memory lifecycle | Partial | No |
| Reliable tool choice | Partial | No |
| Simple task execution | Yes | Partial |
| Multi-step recovery | Improved | No |
| Native open music | Yes (branch) | Needs retest on APK |
| Jarvis-level companion | No | No |

---

## 7. CI evidence (exact head)

- **Success:** [Build APK #3911](https://github.com/Kingijimzy/phoneai/actions/runs/41793317167)  
- **SHA:** `8a0c4af34a4fc70dfd9129d095ac4704e01a815d`  
- **Artifact:** `PhoneAI-apk`  
- **Failed prior:** #3910 (4 unit tests on resolveDispatchId) — fixed on #3911

Physical-device acceptance: **not yet recorded in this document.**

---

## 8. What we will not do next

- No new agents, trading desks, Soul frameworks, or dashboards.  
- No rewriting working tool implementations without a failing scenario.  
- No claiming Stage 5 while Stage 4 (reliable execution + eval) is unproven.

**Priority:** smarter context, memory, tool choice, and recovery — not a larger product surface.
