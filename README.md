# Signal Engine

> A self-hosted swing-trading research platform for Indian equities.
> Scans NSE indices nightly, scores setups across technicals, fundamentals, sentiment, and earnings-call analysis, and emails a shortlist with precise entry, stop, and target levels.

**Status:** Work in progress — actively building. The pipeline runs end-to-end; tests, CI, and indicator optimizations are next.

> ⚠️ **This is a decision-support tool, not an auto-trader.** It produces a shortlist. Every order is still placed manually. Nothing here is investment advice.

---

## Why this exists

Manually scanning 250+ stocks every evening for clean swing setups is the kind of task that's repetitive enough to skip on a busy day — and skipping it is how you miss the move. I wanted a system that does the boring filtering for me, applies the same rules every night, and hands me a small list of candidates I can spend real time on.

---

## What it does

Every weekday at 9 PM IST, the pipeline:

1. **Checks market regime** using NIFTYBEES — pauses scanning in Bear or Volatile conditions.
2. **Scans** Nifty 50 → Next 50 → Midcap 100 → Midcap 150 → Smallcap 250 in priority order.
3. **Applies hard mechanical gates** (uptrend, ATR sanity bounds, HH/HL price structure, max-risk %). Failures are rejected before any expensive computation.
4. **Composite-scores survivors** by combining technicals, fundamentals, news sentiment, and earnings-call analysis.
5. **Refines entries** by finding real swing pivots and clustering nearby support/resistance into precise stop and target levels.
6. **Emails a shortlist** of the top setups with all the numbers needed to place a trade.

A separate backtester and walk-forward validator let me check whether any candidate strategy actually has an edge before trusting it live.

---

## Architecture

```
                  ┌─────────────────────────┐
                  │   Trade Tracker UI      │
                  │   (HTML/CSS/JS, nginx)  │
                  └────────────┬────────────┘
                               │ REST
                               ▼
┌──────────────────────────────────────────────────────┐
│              Java Service (Spring Boot)              │
│  • Signal engine + strategy pathways                 │
│  • Backtester + walk-forward validator               │
│  • Layer 1 gates → Layer 3 S/R refinement            │
│  • Daily scheduler, email alerts, SQLite persistence │
└────────────────────────┬─────────────────────────────┘
                         │ HTTP
                         ▼
┌──────────────────────────────────────────────────────┐
│           Python Service (Flask)                     │
│  • Yahoo Finance candle data + caching               │
│  • Layer 2: fundamentals, sentiment, concall RAG     │
│  • 5-tier LLM fallback chain                         │
│  • ChromaDB vector store for concall transcripts     │
└──────────────────────────────────────────────────────┘
```

**Why this split:** Java handles the deterministic, latency-sensitive work (indicators, backtests, scoring) where a strongly-typed core pays off. Python owns the messy data integration — yfinance, BSE/NSE APIs, news RSS, LLM calls, embeddings — where flexibility matters more than throughput.

---

## Tech stack

**Java service**
- Java 17, Spring Boot 3.5
- SQLite + Hibernate (community SQLite dialect)
- Spring Scheduling, Spring Mail
- Lombok

**Python service**
- Flask + Gunicorn
- yfinance, pandas, feedparser
- ChromaDB + sentence-transformers (`all-MiniLM-L6-v2`) for concall RAG
- pdfminer.six for transcript extraction

**LLM providers (5-tier fallback)**
Gemini 2.5 Flash → Groq Llama 3.3 70B → Cerebras gpt-oss-120b → OpenRouter → GitHub Models gpt-4o. Any single provider's rate limit can fail without taking the pipeline down.

**Infra**
Docker Compose, nginx, deployable on any Ubuntu/Debian host (designed for Oracle Cloud Free Tier).

---

## The interesting bits

### Realistic Indian cost model

Most retail backtests assume a flat 0.1% commission and call it a day. Signal Engine models the actual charges a delivery trade incurs on Zerodha/Groww/Upstox:

- STT (0.1% on sell side)
- NSE transaction charges (0.00297% per leg)
- GST (18% on brokerage + exchange + SEBI)
- SEBI charges (0.0001% per leg)
- Stamp duty (0.015% on buy side)
- Entry/exit slippage (0.25% each by default)

Backtest P&L matches what would actually land in the account.

### Walk-forward validation

Standard backtests fit and evaluate on the same data. The `WalkForwardService` slices candles into rolling `[train | test]` windows with a 200-bar warmup so SMA200 is populated from the first bar of the window. The aggregate test result is a more honest estimate of out-of-sample edge.

### Layered pipeline with early rejection

```
Live Scan → [Hard Gates] → [Setup Detection] → [Scoring] → [Sector Dedup]
              ↓ pass         ↓ pass             ↓ pass      ↓ keep best per sector
            Layer 2 (composite score: technicals + fundamentals + sentiment + concall)
              ↓ score ≥ threshold
            Layer 3 (precise entry / stop / target via swing pivots + S/R clusters)
```

Each stage rejects fast. The LLM is only called for stocks that already passed mechanical gates — keeps free-tier quotas under control.

### Wilder-smoothed RSI

Implemented with Wilder's smoothing (carries `avgGain` / `avgLoss` forward) instead of the simple-average version most tutorials show. Values match TradingView and Zerodha Kite.

### Resilient data sourcing

Every upstream fails differently every week. The Python service has fallback chains for candles, fundamentals, sentiment (Google News RSS → Economic Times → MoneyControl), and concall transcripts (BSE XML API → NSE announcements → Screener.in → Google News). Caching with TTL keeps things reasonable.

---

## Project layout

```
signal-engine/
├── java-service/                Spring Boot — engine, backtester, API, scheduler
│   ├── src/main/java/.../Indicators/      ATR, RSI, SMA, Bollinger Bands
│   ├── src/main/java/.../Service/         Backtest, LiveScan, Layer2/3, MarketRegime, Trade, WalkForward, Email
│   ├── src/main/java/.../controller/      REST endpoints
│   ├── src/main/java/.../model/           DTOs
│   └── src/main/resources/                application.properties
├── python-service/              Flask — data, sentiment, fundamentals, concall RAG
│   ├── market_service.py                  Flask app + endpoints
│   ├── fundamentals_fetcher.py
│   ├── sentiment_analyser.py
│   ├── concall_analyser.py + concall_store.py
│   ├── composite_scorer.py
│   ├── llm_client.py                      5-tier fallback
│   └── cache.py
├── trade_tracker.html           Custom dashboard (no framework)
├── docker-compose.yml
├── deploy.sh                    First-time + update deployment
└── nginx.conf
```

---

## Running it

### Prerequisites
- Docker + Docker Compose
- At least one LLM API key (Gemini recommended — generous free tier)

### Setup

```bash
git clone <repo> signal-engine
cd signal-engine

# Configure secrets
cp python-service/.env.example python-service/.env
# Edit python-service/.env and set at least GEMINI_API_KEY

# Boot the stack
./deploy.sh
```

Services:
- Dashboard: <http://localhost>
- Java API: <http://localhost:8080>
- Python service: <http://localhost:5000/health>

### Updating

```bash
./deploy.sh update
```

### Running services without Docker (development)

```bash
# Java
cd java-service
./mvnw spring-boot:run

# Python (separate terminal)
cd python-service
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python market_service.py
```

---

## Configuration

Strategy thresholds, risk parameters, scan schedules, and indicator settings all live in `java-service/src/main/resources/application.properties` and can be overridden via environment variables. A few you'll likely want to tune:

| Property | Default | What it does |
|---|---|---|
| `backtest.risk.per.trade` | `0.01` | Fraction of capital risked per position |
| `live.scan.atr.pct.max` | `6.0` | Reject stocks with ATR > 6% (too volatile) |
| `live.scan.setup.min.score` | `50` | Minimum score to surface a setup |
| `regime.bear.drawdown.pct` | `10.0` | Drawdown threshold for bear regime |
| `scan.schedule.cron` | `0 30 15 * * *` | Daily scan time (15:30 UTC = 9 PM IST) |

LLM provider keys go in `python-service/.env`:

```
GEMINI_API_KEY=...
GROQ_API_KEY=...
CEREBRAS_API_KEY=...
OPENROUTER_API_KEY=...
GITHUB_TOKEN=...
```

Only one is required — the rest are fallbacks.

---

## Roadmap

- Comprehensive unit test coverage for indicators, cost model, and strategy pathways
- GitHub Actions CI (build + test on PR)
- Roll indicator computations to O(n) instead of O(n × period) — meaningful for 5-year backtests
- Replace `ThreadLocal` ATR-passing with a richer `Signal` return type from the strategy interface
- Per-sector parameter calibration via walk-forward
- Position-level Kelly sizing as an option

---

## Disclaimer

This is a research and decision-support tool built for personal use. It does not place orders. It is not investment advice. Past backtest performance — even out-of-sample — is not a guarantee of future results. Do your own due diligence before risking capital.