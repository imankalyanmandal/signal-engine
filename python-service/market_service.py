"""
Signal Engine — Market Data + Layer 2 Microservice

Endpoints:
  GET /candles?symbol=TECHM&period=1y       — OHLCV candle data (Layer 1)
  GET /nifty50/symbols?index=NIFTY+100      — live index constituents
  GET /index/symbols?index=NIFTY+100        — same, more explicit name
  GET /layer2/analyse?symbol=TECHM          — full Layer 2 analysis (single stock)
  GET /layer2/scan?symbols=TECHM,HDFCBANK   — Layer 2 scan across a list
  GET /health                               — health check

Install:
  pip install flask yfinance pandas requests feedparser pdfminer.six python-dotenv

Run:
  python market_service.py
"""

# ── Flask app must be created before anything that uses @app.route ────────────
from flask import Flask, jsonify, request
app = Flask(__name__)

# ── Standard library ──────────────────────────────────────────────────────────
import time

# ── Third-party ───────────────────────────────────────────────────────────────
import yfinance as yf

# ── Local modules (imported AFTER app is created) ────────────────────────────
from claude_config         import is_mock
from fundamentals_fetcher  import fetch_fundamentals
from sentiment_analyser    import fetch_sentiment
from concall_analyser      import fetch_concall_analysis
from concall_store         import get_history, search_similar, store_stats
from composite_scorer      import compute_composite_score, get_company_name
from symbol_provider       import get_symbols, to_yahoo_ticker
from cache                 import (get_candles as cache_get_candles,
                                   set_candles as cache_set_candles,
                                   invalidate_symbols, invalidate_candles,
                                   cache as _cache)

# ─────────────────────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────────────────────

VALID_PERIODS  = {"5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"}
VALID_EXCHANGES = {"NS", "BO"}
MAX_RETRIES    = 3
RETRY_DELAY    = 2   # seconds between Yahoo Finance retries


# ─────────────────────────────────────────────────────────────────────────────
# Layer 1 — candle data
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/candles")
def get_candles():
    """
    Fetch OHLCV candle data from Yahoo Finance.

    GET /candles?symbol=TECHM&period=1y&exchange=NS
    """
    symbol   = request.args.get("symbol",   "").strip().upper()
    period   = request.args.get("period",   "1y")
    exchange = request.args.get("exchange", "NS")

    if not symbol:
        return jsonify({"error": "symbol is required"}), 400
    if period not in VALID_PERIODS:
        return jsonify({"error": f"Invalid period. Use: {sorted(VALID_PERIODS)}"}), 400
    if exchange not in VALID_EXCHANGES:
        return jsonify({"error": "exchange must be NS (NSE) or BO (BSE)"}), 400

    # ── Cache check (4-hour TTL) ──────────────────────────────────────────────
    cache_key = f"{symbol}_{exchange}"
    cached = cache_get_candles(cache_key, period)
    if cached:
        print(f"  [Cache HIT] {symbol}.{exchange} {period} ({len(cached)} bars)")
        return jsonify(cached)

    # ── Fetch from Yahoo Finance and cache result ──────────────────────────────
    ticker    = to_yahoo_ticker(symbol, exchange)
    result    = _fetch_candles_with_retry(symbol, ticker, period)

    # _fetch_candles_with_retry returns either:
    #   Response          (success, status 200)
    #   (Response, 404)   (failure tuple)
    # Unpack correctly to avoid AttributeError on tuple
    if isinstance(result, tuple):
        return result   # error response — return as-is

    resp = result
    if resp.status_code == 200:
        import json as _json
        try:
            cache_set_candles(cache_key, period, _json.loads(resp.get_data(as_text=True)))
        except Exception:
            pass   # cache failure is non-fatal

    return resp


@app.route("/health")
def health():
    from llm_client import check_providers
    return jsonify({
        "status":    "ok",
        "mock_mode": is_mock(),
        "layers":    ["layer1_candles", "layer2_fundamental_sentiment_concall"],
        "providers": check_providers(),
        "rag_store": store_stats(),
    })


# ─────────────────────────────────────────────────────────────────────────────
# Index constituents
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/nifty50/symbols")
def get_nifty50_symbols():
    """
    GET /nifty50/symbols                → Nifty 50 (default)
    GET /nifty50/symbols?index=NIFTY+100 → Nifty 100
    """
    index   = request.args.get("index", "NIFTY 50")
    symbols = get_symbols(index)
    return jsonify({
        "index":   index,
        "count":   len(symbols),
        "symbols": symbols,
    })


@app.route("/index/symbols")
def get_index_symbols():
    """
    GET /index/symbols?index=NIFTY+50
    GET /index/symbols?index=NIFTY+NEXT+50
    GET /index/symbols?index=NIFTY+100
    GET /index/symbols?index=NIFTY+200
    """
    index   = request.args.get("index", "NIFTY 100")
    symbols = get_symbols(index)
    return jsonify({
        "index":   index,
        "count":   len(symbols),
        "symbols": symbols,
    })


# ─────────────────────────────────────────────────────────────────────────────
# RAG — concall history and semantic search
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/concalls/history")
def concall_history():
    """
    GET /concalls/history?symbol=HDFCBANK
    GET /concalls/history?symbol=HDFCBANK&n=8

    Returns the last N stored concall analyses for a symbol, newest first.
    Each record includes quarter_label, tone, guidance_change, swing_signal,
    summary, key_positives, key_risks, filing_date, source.
    """
    symbol = request.args.get("symbol", "").strip().upper()
    n      = int(request.args.get("n", 6))
    if not symbol:
        return jsonify({"error": "symbol is required"}), 400
    history = get_history(symbol, n=n)
    return jsonify({
        "symbol":  symbol,
        "count":   len(history),
        "records": history,
    })


@app.route("/concalls/search")
def concall_search():
    """
    GET /concalls/search?symbol=HDFCBANK&q=margin+pressure

    Semantic search over stored concall embeddings.
    Returns the most similar past quarters to the query text.
    Requires ChromaDB + sentence-transformers to be installed.
    """
    symbol = request.args.get("symbol", "").strip().upper()
    query  = request.args.get("q", "").strip()
    n      = int(request.args.get("n", 3))
    if not symbol:
        return jsonify({"error": "symbol is required"}), 400
    if not query:
        return jsonify({"error": "q (query) is required"}), 400
    results = search_similar(symbol, query, n=n)
    return jsonify({
        "symbol":  symbol,
        "query":   query,
        "count":   len(results),
        "results": results,
    })


# ─────────────────────────────────────────────────────────────────────────────
# Layer 2 — fundamental + sentiment + concall analysis
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/layer2/analyse")
def layer2_analyse():
    """
    Full LLM-powered Layer 2 analysis for a single stock.

    GET /layer2/analyse?symbol=TECHM

    Optionally pass Layer 1 backtest results as query params so Claude
    can reason across all signals together:
    GET /layer2/analyse?symbol=TECHM&returnPercent=23.1&sharpeRatio=4.44&winRate=70&totalTrades=10&maxDrawdown=3.6
    """
    symbol = request.args.get("symbol", "").strip().upper()
    if not symbol:
        return jsonify({"error": "symbol is required"}), 400

    layer1 = _parse_layer1_params(request.args)
    company = get_company_name(symbol)

    print(f"\n[Layer 2] Analysing {symbol} ({company})")
    result = _run_layer2(symbol, company, layer1)
    return jsonify(result)


@app.route("/layer2/scan")
def layer2_scan():
    """
    Layer 2 scan across a comma-separated list of symbols.

    GET /layer2/scan?symbols=TECHM,POWERGRID,HDFCBANK

    Optionally send Layer 1 results for each stock as JSON body:
    { "layer1_results": { "TECHM": { "returnPercent": 23.1, ... } } }
    """
    symbols_param = request.args.get("symbols", "")
    if not symbols_param:
        return jsonify({"error": "symbols param required. E.g. ?symbols=TECHM,HDFCBANK"}), 400

    symbols = [s.strip().upper() for s in symbols_param.split(",") if s.strip()]
    if not symbols:
        return jsonify({"error": "No valid symbols provided"}), 400
    if len(symbols) > 20:
        return jsonify({"error": "Maximum 20 symbols per scan"}), 400

    # Optional Layer 1 results map from JSON body
    layer1_map = {}
    try:
        body = request.get_json(silent=True) or {}
        layer1_map = body.get("layer1_results", {})
    except Exception:
        pass

    print(f"\n[Layer 2] Scanning {len(symbols)} stocks: {symbols}")
    results = []

    for symbol in symbols:
        company = get_company_name(symbol)
        layer1  = layer1_map.get(symbol)
        print(f"  Processing {symbol}...")
        result = _run_layer2(symbol, company, layer1)
        results.append(result)
        time.sleep(1.5)  # avoid LLM rate limits between stocks

    # Sort: passing stocks first, then by composite score descending
    results.sort(key=lambda r: (0 if r["layer2_pass"] else 1, -r["composite_score"]))

    return jsonify({
        "total":    len(results),
        "passed":   sum(1 for r in results if r["layer2_pass"]),
        "rejected": sum(1 for r in results if not r["layer2_pass"]),
        "results":  results,
    })


# ─────────────────────────────────────────────────────────────────────────────
# Internal helpers
# ─────────────────────────────────────────────────────────────────────────────

def _run_layer2(symbol: str, company: str, layer1: dict = None) -> dict:
    """Run all three data fetches, then call LLM for holistic scoring."""
    print(f"  [{symbol}] Fetching fundamentals...")
    fund = fetch_fundamentals(symbol)
    time.sleep(0.5)

    print(f"  [{symbol}] Fetching news sentiment...")
    sent = fetch_sentiment(symbol, company)
    time.sleep(0.5)

    print(f"  [{symbol}] Fetching concall...")
    conc = fetch_concall_analysis(symbol, company)
    time.sleep(0.5)

    print(f"  [{symbol}] LLM holistic analysis...")
    result = compute_composite_score(symbol, fund, sent, conc, layer1)

    verdict = "PASS" if result["layer2_pass"] else "REJECT"
    print(f"  [{symbol}] {result['swing_verdict']} | "
          f"score={result['composite_score']} | "
          f"conviction={result['conviction']} | {verdict}")

    return result


def _fetch_candles_with_retry(symbol: str, ticker: str, period: str):
    """
    Fetch candles from Yahoo Finance with retry.

    Uses yf.download() — more reliable than Ticker().history() for NSE symbols.
    Falls back to Ticker().history() if download fails.
    """
    last_error = "Unknown error"

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            print(f"  [{attempt}/{MAX_RETRIES}] Fetching {ticker} period={period}")

            # Method 1: yf.download — most reliable for NSE symbols
            df = yf.download(
                ticker,
                period=period,
                progress=False,
                auto_adjust=True,
                actions=False,
            )

            # yf.download returns MultiIndex columns for single ticker — flatten
            if df is not None and not df.empty:
                if isinstance(df.columns, __import__('pandas').MultiIndex):
                    df.columns = df.columns.get_level_values(0)

                candles = []
                for date, row in df.iterrows():
                    try:
                        candles.append({
                            "date":   date.strftime("%d-%b-%y"),
                            "open":   round(float(row["Open"]),  2),
                            "high":   round(float(row["High"]),  2),
                            "low":    round(float(row["Low"]),   2),
                            "close":  round(float(row["Close"]), 2),
                            "volume": int(row.get("Volume", 0)),
                        })
                    except (KeyError, ValueError):
                        continue

                if candles:
                    print(f"  Returning {len(candles)} candles for {ticker}")
                    return jsonify(candles)

            last_error = f"Empty data returned for {ticker}"
            print(f"  {last_error}")

        except Exception as e:
            last_error = str(e)
            print(f"  Attempt {attempt} failed for {ticker}: {last_error[:200]}")

        time.sleep(RETRY_DELAY * attempt)   # exponential backoff: 2s, 4s, 6s

    return jsonify({"error": f"No data for {symbol}: {last_error}"}), 404


def _parse_layer1_params(args) -> dict:
    """Extract optional Layer 1 backtest metrics from query params."""
    try:
        l1 = {}
        for key in ("returnPercent", "sharpeRatio", "winRate",
                    "totalTrades", "maxDrawdown", "profitFactor"):
            val = args.get(key)
            if val is not None:
                l1[key] = float(val)
        return l1 if l1 else None
    except (ValueError, TypeError):
        return None


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

# ── Cache management endpoints ────────────────────────────────────────────────

@app.route("/cache/status")
def cache_status():
    """GET /cache/status — show cache stats and all cached keys."""
    return jsonify({
        "stats": _cache.stats(),
        "keys":  _cache.keys_info(),
    })

@app.route("/cache/invalidate/symbols", methods=["POST"])
def invalidate_symbols_endpoint():
    """POST /cache/invalidate/symbols?index=NIFTY+50 — force symbol list refresh."""
    index   = request.args.get("index")
    deleted = invalidate_symbols(index)
    return jsonify({
        "status":  "ok",
        "deleted": deleted,
        "message": f"Symbol cache cleared for {'index=' + index if index else 'ALL indices'}",
    })

@app.route("/cache/invalidate/candles", methods=["POST"])
def invalidate_candles_endpoint():
    """POST /cache/invalidate/candles?symbol=HDFCBANK — force candle data refresh."""
    symbol  = request.args.get("symbol")
    deleted = invalidate_candles(symbol)
    return jsonify({
        "status":  "ok",
        "deleted": deleted,
        "message": f"Candle cache cleared for {'symbol=' + symbol if symbol else 'ALL symbols'}",
    })

@app.route("/cache/invalidate/all", methods=["POST"])
def invalidate_all():
    """POST /cache/invalidate/all — clear entire cache."""
    count = _cache.clear_all()
    return jsonify({"status": "ok", "deleted": count, "message": "Cache fully cleared"})


if __name__ == "__main__":
    print("\n=== Signal Engine Market Service ===")
    print("Layer 1: /candles, /nifty50/symbols, /index/symbols")
    print("Layer 2: /layer2/analyse, /layer2/scan")
    print("Health:  /health")
    print("Running on http://localhost:5000\n")
    app.run(host="0.0.0.0", port=5000, debug=False)