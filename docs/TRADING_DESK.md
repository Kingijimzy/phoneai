# Orion Trading Desk

Permissioned trader companion on top of existing `TradingActivity`, `Bybit`, `WatchService`, and `TradeHistoryStore`.

## Branch status (`feature/trading-desk-phase-a`)

| Piece | Status |
|--------|--------|
| `trading_research` / `trading_execution` packs | Done |
| GatePolicy order rules | Done |
| Playbook / Matcher / Risk pure modules | Done |
| `TradingDeskStore` + `TradingExecution` | Done |
| `TradingToolHandlers` + `ToolRegistry` | Done |
| **Agent.kt dispatch** | **Done** (wired to handlers) |
| TradingActivity playbook UI | Done |
| Forex execution | Not started |
| Device testnet proof | Not done |

## How to trade safely

1. Trading settings: prefer **testnet**, save Read+Trade API keys (no withdraw).
2. Agent Control: enable **Trading execution** pack.
3. Turn off read-only when you want confirmed orders.
4. Every order: confirmation + risk card (max order, allowlist from watchlist).
5. Kill path: read-only on, or disable execution pack, or STOP trusted session.

## Strategy learning

1. Chat: strategy note / chart note → `trading_strategy_note`
2. Pending candidate stored locally
3. Trading screen → **Accept** → enabled rule
4. **Match now** or `trading_match_setups` scores vs watchlist

## Agent tools (chat)

Research: `bybit_public_ticker`, `bybit_public_kline`, watchlist, playbook, match, strategy note, trade history.

Execution (pack + confirm): `bybit_place_order`, `bybit_cancel_order`.

## Honesty

- Do not claim auto-trade.
- Confirmation remains required unless Trusted session covers execution.
- Testnet first on device before real money.
