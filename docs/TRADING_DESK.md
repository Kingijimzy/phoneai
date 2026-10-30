# Orion Trading Desk

Permissioned trader companion on top of existing `TradingActivity`, `Bybit`, `WatchService`, and `TradeHistoryStore`.

## What already existed on main

- Bybit V5 public tickers/klines + signed balance/positions/**order**/cancel/close
- Trading settings UI (keys, testnet, read-only, max order, watchlist, CSV import)
- Background market watch service

## What this branch adds

| Piece | Role |
|--------|------|
| `trading_research` vs `trading_execution` packs | Research default-on; execution opt-in |
| GatePolicy | Research never places orders; execution needs approval/trust |
| TradingPlaybook / TradingMatcher / TradingRiskPolicy | Pure strategy + setup match + risk envelope |
| TradingDeskStore | Playbook candidates/rules + risk bridge to Prefs |
| TradingExecution | **Only** gateway before `Bybit.order` |

## How to trade safely

1. Trading settings: prefer **testnet**, save Read+Trade API keys (no withdraw).
2. Agent Control: enable **Trading execution** pack.
3. Turn off read-only when you want confirmed orders.
4. Every order: confirmation + risk card (max order, allowlist from watchlist).
5. Kill path: read-only on, or disable execution pack, or STOP trusted session.

## Strategy learning

1. Describe a chart / result → `TradingPlaybook.candidateFromNote`
2. Store via `TradingDeskStore.proposeCandidate`
3. User Accept → enabled rule
4. `TradingDeskStore.matchWatchlist` scores rules against live Bybit public data

## Forex

Not live yet. Same packs/gates later with a real broker adapter — no password scrape.

## Honesty

- CI unit tests cover pure policy/matcher/risk.
- Device verification of live orders is still on you (testnet first).
- Do not claim auto-trade; confirmation remains required unless Trusted session covers execution.
