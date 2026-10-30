# Orion Trading Desk

Permissioned trader companion: learn strategy, watch markets, match setups, news context, execute only with user approval.

## Status

| Layer | Status |
|--------|--------|
| `trading_research` pack | Implemented (public data, journal tools path, default on) |
| Playbook / Matcher / Risk pure logic | Phase A on `feature/trading-desk-phase-a` |
| `trading_execution` pack | Declared, **implemented=false** until Bybit order adapter ships |
| Order tools in GatePolicy | Research DENY; execution requires approval/trust |
| Watcher automation | Planned Phase B |
| Bybit live orders | Planned Phase C |
| Forex broker | Planned Phase D |

## Rules

- No silent auto-trade.
- Order tools never run under `trading_research`.
- `trading_execution` default off; not enableable until `implemented=true`.
- Risk envelope: kill switch, paper mode (default on in pure Limits), max notional, daily loss, open trades, symbol allowlist.
- Strategy growth: candidates need user Accept.
- News only via tools with sources — no invented NFP/numbers.

## Phase roadmap

1. **A (this branch)** — packs, GatePolicy, pure playbook/matcher/risk, tests, docs.
2. **B** — persistence UI, watchlist, Soul/Watcher hooks, proactive setup cards.
3. **C** — vault keys, Bybit signed REST, mark `trading_execution` implemented, wire tools.
4. **D** — one forex broker API, same gates.
5. **E** — journal lessons → playbook propose (Propose–Probe–Compose).

## Agent Control

User enables packs under Agent control. Trading execution must stay off until Phase C and risk prefs are set.
