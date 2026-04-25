"""
Layer 2 — Fundamentals Fetcher (with quarterly trend analysis)

Data source: Yahoo Finance (yfinance) — swap to Zerodha later by replacing
the _fetch_yahoo_* functions below. Everything above that is provider-agnostic.

Quarterly rules added:
  1. Promoter holding — decline after 2yr increase = red flag
  2. Revenue trend    — QoQ and YoY must both be positive
  3. Debt trend       — must be decreasing QoQ and YoY
  4. OCF trend        — operating cash flow must be improving

Promoter holding history: BSE shareholding API (quarterly, public, no auth)
All other quarterly data:  yfinance quarterly_financials / balance_sheet / cashflow
"""

import yfinance as yf
import requests
from typing import Optional, List

# ── Yahoo Finance ticker map ──────────────────────────────────────────────────
NIFTY_YAHOO = {
    "ADANIENT":   "ADANIENT.NS",   "ADANIPORTS": "ADANIPORTS.NS",
    "APOLLOHOSP": "APOLLOHOSP.NS", "ASIANPAINT": "ASIANPAINT.NS",
    "AXISBANK":   "AXISBANK.NS",   "BAJAJ-AUTO": "BAJAJ-AUTO.NS",
    "BAJFINANCE": "BAJFINANCE.NS", "BAJAJFINSV": "BAJAJFINSV.NS",
    "BPCL":       "BPCL.NS",       "BHARTIARTL": "BHARTIARTL.NS",
    "BRITANNIA":  "BRITANNIA.NS",  "CIPLA":      "CIPLA.NS",
    "COALINDIA":  "COALINDIA.NS",  "DIVISLAB":   "DIVISLAB.NS",
    "DRREDDY":    "DRREDDY.NS",    "EICHERMOT":  "EICHERMOT.NS",
    "GRASIM":     "GRASIM.NS",     "HCLTECH":    "HCLTECH.NS",
    "HDFCBANK":   "HDFCBANK.NS",   "HDFCLIFE":   "HDFCLIFE.NS",
    "HEROMOTOCO": "HEROMOTOCO.NS", "HINDALCO":   "HINDALCO.NS",
    "HINDUNILVR": "HINDUNILVR.NS", "ICICIBANK":  "ICICIBANK.NS",
    "ITC":        "ITC.NS",        "INDUSINDBK": "INDUSINDBK.NS",
    "INFY":       "INFY.NS",       "JSWSTEEL":   "JSWSTEEL.NS",
    "KOTAKBANK":  "KOTAKBANK.NS",  "LT":         "LT.NS",
    "M&M":        "MM.NS",         "MARUTI":     "MARUTI.NS",
    "NESTLEIND":  "NESTLEIND.NS",  "NTPC":       "NTPC.NS",
    "ONGC":       "ONGC.NS",       "POWERGRID":  "POWERGRID.NS",
    "RELIANCE":   "RELIANCE.NS",   "SBILIFE":    "SBILIFE.NS",
    "SHRIRAMFIN": "SHRIRAMFIN.NS", "SBIN":       "SBIN.NS",
    "SUNPHARMA":  "SUNPHARMA.NS",  "TCS":        "TCS.NS",
    "TATACONSUM": "TATACONSUM.NS", "TATAMOTORS": "TATAMOTORS.NS",
    "TATASTEEL":  "TATASTEEL.NS",  "TECHM":      "TECHM.NS",
    "TITAN":      "TITAN.NS",      "ULTRACEMCO": "ULTRACEMCO.NS",
    "WIPRO":      "WIPRO.NS",      "ZOMATO":     "ZOMATO.NS",
}

# ── BSE scrip codes for promoter holding ─────────────────────────────────────
BSE_CODES = {
    "TECHM":      "532755", "POWERGRID":  "532898", "HDFCBANK":   "500180",
    "DIVISLAB":   "532488", "TITAN":      "500114", "COALINDIA":  "533278",
    "SBIN":       "500112", "HCLTECH":    "532281", "HINDALCO":   "500440",
    "INFY":       "500209", "AXISBANK":   "532215", "ICICIBANK":  "532174",
    "RELIANCE":   "500325", "TCS":        "532540", "WIPRO":      "507685",
    "BAJFINANCE": "500034", "KOTAKBANK":  "500247", "ADANIPORTS": "532921",
    "TATAMOTORS": "500570", "MARUTI":     "532500", "SUNPHARMA":  "524715",
    "DRREDDY":    "500124", "CIPLA":      "500087", "HEROMOTOCO": "500182",
    "BAJAJFINSV": "532978", "NESTLEIND":  "500790", "BRITANNIA":  "500825",
    "JSWSTEEL":   "500228", "TATASTEEL":  "500470", "NTPC":       "532555",
    "ONGC":       "500312", "BPCL":       "500547", "LT":         "500510",
    "EICHERMOT":  "505200", "ASIANPAINT": "500820", "ULTRACEMCO": "532538",
    "GRASIM":     "500300", "APOLLOHOSP": "508869", "INDUSINDBK": "532187",
    "ITC":        "500875", "BHARTIARTL": "532454", "HDFCLIFE":   "540777",
    "SBILIFE":    "540719", "SHRIRAMFIN": "511218", "BAJAJ-AUTO": "532977",
    "ADANIENT":   "512599", "TATACONSUM": "500800", "HINDUNILVR": "500696",
    "ZOMATO":     "543320", "MM":         "500520",
}

BSE_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    "Accept":     "application/json",
    "Referer":    "https://www.bseindia.com/",
}


# ── Public API ────────────────────────────────────────────────────────────────

def fetch_fundamentals(symbol: str) -> dict:
    """
    Fetch snapshot fundamentals + quarterly trend analysis.
    Returns a single dict used by composite_scorer.py.

    To swap from Yahoo Finance to Zerodha later:
      - Replace _fetch_yahoo_snapshot() with your Kite Connect call
      - Replace _fetch_yahoo_quarterly() with Zerodha fundamentals API
      - Keep everything below this function unchanged
    """
    ticker_sym = NIFTY_YAHOO.get(symbol, f"{symbol}.NS")

    # ── Snapshot metrics (ROE, PE, margins etc.) ──────────────────────────────
    snapshot = _fetch_yahoo_snapshot(ticker_sym)

    # ── Quarterly trends (revenue, debt, OCF) ─────────────────────────────────
    trends = _fetch_yahoo_quarterly(ticker_sym)

    # ── Promoter holding history (BSE) ────────────────────────────────────────
    promoter = _fetch_promoter_trend(symbol)

    # ── Score everything ──────────────────────────────────────────────────────
    return _score(symbol, snapshot, trends, promoter)


# ── Data fetchers (swap these for Zerodha later) ──────────────────────────────

def _fetch_yahoo_snapshot(ticker_sym: str) -> dict:
    """Fetch current-period fundamentals from Yahoo Finance."""
    try:
        info = yf.Ticker(ticker_sym).info
        if not info or info.get("regularMarketPrice") is None:
            return {"error": True, "msg": f"No data for {ticker_sym}"}
        return {
            "error":          False,
            "roe":            _pct(info.get("returnOnEquity")),
            "debt_equity":    _de(info.get("debtToEquity")),
            "profit_margin":  _pct(info.get("profitMargins")),
            "revenue_growth": _pct(info.get("revenueGrowth")),      # YoY from Yahoo
            "earnings_growth":_pct(info.get("earningsGrowth")),
            "current_ratio":  info.get("currentRatio"),
            "pe_ratio":       info.get("trailingPE"),
            "market_cap":     info.get("marketCap"),
        }
    except Exception as e:
        return {"error": True, "msg": str(e)}


def _fetch_yahoo_quarterly(ticker_sym: str) -> dict:
    """
    Fetch last 4-8 quarters of revenue, debt, and operating cash flow.

    Yahoo returns columns newest-first; we reverse to chronological order.
    Values are in the company's reporting currency (INR for Indian stocks).

    To swap to Zerodha: replace this function body with Kite/Tijori API calls
    and return the same dict shape.
    """
    result = {
        "error":               False,
        "revenue_series":      [],   # list of quarterly revenue (oldest → newest)
        "debt_series":         [],
        "ocf_series":          [],
        "capex_series":        [],
    }

    try:
        t = yf.Ticker(ticker_sym)

        # Revenue / net income
        fin = t.quarterly_financials
        if fin is not None and not fin.empty:
            for label in ["Total Revenue", "TotalRevenue"]:
                if label in fin.index:
                    result["revenue_series"] = _to_crores_list(fin.loc[label])
                    break

        # Balance sheet — total debt
        bs = t.quarterly_balance_sheet
        if bs is not None and not bs.empty:
            for label in ["Total Debt", "TotalDebt", "Long Term Debt"]:
                if label in bs.index:
                    result["debt_series"] = _to_crores_list(bs.loc[label])
                    break

        # Cash flow
        cf = t.quarterly_cashflow
        if cf is not None and not cf.empty:
            for label in ["Operating Cash Flow", "OperatingCashFlow"]:
                if label in cf.index:
                    result["ocf_series"] = _to_crores_list(cf.loc[label])
                    break
            for label in ["Capital Expenditure", "CapitalExpenditure"]:
                if label in cf.index:
                    result["capex_series"] = _to_crores_list(cf.loc[label])
                    break

    except Exception as e:
        result["error"]     = True
        result["error_msg"] = str(e)

    return result


def _fetch_promoter_trend(symbol: str) -> dict:
    """
    Fetch last 8 quarters of promoter holding % from BSE shareholding API.

    Returns chronological list (oldest → newest) and derived flags.
    Promoter holding is not available on Yahoo Finance for Indian stocks.
    """
    scrip_cd = BSE_CODES.get(symbol)
    if not scrip_cd:
        return {"error": True, "holdings": [], "msg": "BSE code not found"}

    try:
        url  = (
            "https://api.bseindia.com/BseIndiaAPI/api/ShareholdingPatternData/w"
            f"?scrip_cd={scrip_cd}&type=quarterly"
        )
        resp = requests.get(url, headers=BSE_HEADERS, timeout=10)

        if resp.status_code != 200:
            return {"error": True, "holdings": [], "msg": f"BSE API {resp.status_code}"}

        data     = resp.json()
        rows     = data.get("Table", []) or data.get("data", [])
        holdings = []

        for row in rows[:8]:  # up to 8 quarters
            pct = (row.get("PromoterHolding") or
                   row.get("promoter_holding") or
                   row.get("Promoter"))
            if pct is not None:
                try:
                    holdings.append(round(float(pct), 2))
                except (ValueError, TypeError):
                    pass

        # BSE returns newest-first — reverse to chronological
        holdings = holdings[::-1]

        return {"error": False, "holdings": holdings}

    except Exception as e:
        return {"error": True, "holdings": [], "msg": str(e)}


# ── Scoring engine ────────────────────────────────────────────────────────────

def _score(symbol: str, snap: dict, trends: dict, promoter: dict) -> dict:
    """
    Combine snapshot + quarterly trends into a single fundamental score.
    Each dimension is scored 0-100, then weighted.
    """
    flags       = []
    trend_notes = []

    # ── 1. Snapshot scores ────────────────────────────────────────────────────
    roe            = snap.get("roe")
    debt_equity    = snap.get("debt_equity")
    profit_margin  = snap.get("profit_margin")
    revenue_growth = snap.get("revenue_growth")
    earnings_growth= snap.get("earnings_growth")
    current_ratio  = snap.get("current_ratio")

    roe_score    = _score_roe(roe)
    debt_score   = _score_debt(debt_equity, flags)
    margin_score = _score_margin(profit_margin)
    rev_score    = _score_growth(revenue_growth)
    earn_score   = _score_growth(earnings_growth)

    # ── 2. Revenue quarterly trend ────────────────────────────────────────────
    rev_series  = trends.get("revenue_series", [])
    rev_trend_score, rev_flag, rev_note = _score_quarterly_trend(
        rev_series, "Revenue", positive_is="increasing"
    )
    if rev_flag: flags.append(rev_flag)
    if rev_note: trend_notes.append(rev_note)

    # ── 3. Debt quarterly trend ───────────────────────────────────────────────
    debt_series = trends.get("debt_series", [])
    debt_trend_score, debt_flag, debt_note = _score_quarterly_trend(
        debt_series, "Debt", positive_is="decreasing"
    )
    if debt_flag: flags.append(debt_flag)
    if debt_note: trend_notes.append(debt_note)

    # ── 4. OCF quarterly trend ────────────────────────────────────────────────
    ocf_series  = trends.get("ocf_series", [])
    ocf_trend_score, ocf_flag, ocf_note = _score_quarterly_trend(
        ocf_series, "OCF", positive_is="increasing"
    )
    if ocf_flag: flags.append(ocf_flag)
    if ocf_note: trend_notes.append(ocf_note)

    # FCF = OCF - Capex (for context, not scored separately)
    capex_series = trends.get("capex_series", [])
    fcf_latest   = None
    if ocf_series and capex_series:
        try:
            fcf_latest = round(ocf_series[-1] - abs(capex_series[-1]), 1)
        except Exception:
            pass

    # ── 5. Promoter holding trend ─────────────────────────────────────────────
    holdings       = promoter.get("holdings", [])
    promoter_score, promoter_flag, promoter_note = _score_promoter_trend(holdings)
    if promoter_flag: flags.append(promoter_flag)
    if promoter_note: trend_notes.append(promoter_note)

    # ── 6. Red flag penalties ─────────────────────────────────────────────────
    if current_ratio is not None and current_ratio < 1.0:
        flags.append("LIQUIDITY_RISK")

    # ── 7. Weighted composite fundamental score ───────────────────────────────
    #
    # Snapshot weights (60% total):
    #   ROE 20%, Debt snapshot 15%, Margin 10%, Rev growth 10%, Earn growth 5%
    #
    # Quarterly trend weights (40% total):
    #   Revenue trend 15%, Debt trend 10%, OCF trend 10%, Promoter 5%
    #
    score = (
        roe_score          * 0.20 +
        debt_score         * 0.15 +
        margin_score       * 0.10 +
        rev_score          * 0.10 +
        earn_score         * 0.05 +
        rev_trend_score    * 0.15 +
        debt_trend_score   * 0.10 +
        ocf_trend_score    * 0.10 +
        promoter_score     * 0.05
    )

    # Penalty for critical flags
    if "PROMOTER_SELLING_AFTER_ACCUMULATION" in flags: score *= 0.75
    if "REVENUE_DECLINING_QOQ_AND_YOY"       in flags: score *= 0.85
    if "DEBT_INCREASING_QOQ_AND_YOY"         in flags: score *= 0.85
    if "OCF_DECLINING"                        in flags: score *= 0.90
    if "HIGH_DEBT"                            in flags: score *= 0.85

    fundamental_score = round(min(100, max(0, score)), 1)

    # ── Return ────────────────────────────────────────────────────────────────
    return {
        "symbol":               symbol,
        "source":               "yahoo_finance + bse_shareholding",
        "error":                snap.get("error", False),

        # Snapshot metrics
        "roe":                  roe,
        "debt_equity":          debt_equity,
        "profit_margin":        profit_margin,
        "revenue_growth_3y":    revenue_growth,
        "profit_growth_3y":     earnings_growth,
        "current_ratio":        current_ratio,
        "promoter_holding":     holdings[-1] if holdings else None,

        # Quarterly series (for LLM context)
        "revenue_series":       rev_series[-4:],    # last 4 quarters
        "debt_series":          debt_series[-4:],
        "ocf_series":           ocf_series[-4:],
        "fcf_latest_cr":        fcf_latest,
        "promoter_series":      holdings[-8:],      # last 8 quarters

        # Trend verdicts (human-readable for LLM)
        "trend_notes":          trend_notes,

        # Scores
        "fundamental_score":    fundamental_score,
        "flags":                flags,
    }


# ── Quarterly trend scorer ─────────────────────────────────────────────────────

def _score_quarterly_trend(series: list, name: str, positive_is: str):
    """
    Score a quarterly time series (oldest → newest).

    positive_is: "increasing" or "decreasing"

    Returns (score 0-100, flag_string_or_None, note_string_or_None)
    """
    if len(series) < 2:
        return 50, None, f"{name}: insufficient quarterly data"

    latest   = series[-1]
    prev_q   = series[-2]
    prev_yr  = series[-4] if len(series) >= 4 else None

    if positive_is == "increasing":
        qoq_ok  = latest > prev_q
        yoy_ok  = (latest > prev_yr) if prev_yr is not None else None
    else:  # decreasing
        qoq_ok  = latest < prev_q
        yoy_ok  = (latest < prev_yr) if prev_yr is not None else None

    # Score
    if qoq_ok and yoy_ok:
        score = 90
        note  = f"{name}: improving QoQ and YoY ✓"
        flag  = None
    elif qoq_ok and yoy_ok is None:
        score = 70
        note  = f"{name}: improving QoQ (YoY data unavailable)"
        flag  = None
    elif qoq_ok and not yoy_ok:
        score = 55
        note  = f"{name}: improving QoQ but still below last year"
        flag  = None
    elif not qoq_ok and yoy_ok:
        score = 45
        note  = f"{name}: declined QoQ but still above last year"
        flag  = None
    else:  # both bad
        score = 10
        flag_name = (f"{name.upper().replace(' ','_')}_DECLINING_QOQ_AND_YOY"
                     if positive_is == "increasing"
                     else f"{name.upper().replace(' ','_')}_INCREASING_QOQ_AND_YOY")
        flag  = flag_name
        note  = f"{name}: declining both QoQ and YoY ✗"

    return score, flag, note


# ── Promoter trend scorer ──────────────────────────────────────────────────────

def _score_promoter_trend(holdings: list):
    """
    Score promoter holding trend.

    Rule: if holdings increased over the past 2 years (8 quarters)
    but is now declining, that is a serious red flag — insiders are exiting
    after accumulating.
    """
    if len(holdings) < 2:
        return 50, None, "Promoter holding: insufficient history"

    latest   = holdings[-1]
    prev_q   = holdings[-2]
    qoq_ok   = latest >= prev_q   # stable or increasing is fine

    # 2-year trend check (need at least 8 quarters)
    increased_2yr = False
    if len(holdings) >= 8:
        increased_2yr = holdings[-4] > holdings[-8]  # increased last 2 years

    note = f"Promoter holding: {latest:.1f}% (prev {prev_q:.1f}%)"

    if not qoq_ok and increased_2yr:
        # Selling after accumulation — serious warning
        return (
            20,
            "PROMOTER_SELLING_AFTER_ACCUMULATION",
            f"Promoter holding DECLINING after 2yr increase ({holdings[-8]:.1f}% → {latest:.1f}%) ✗✗"
        )
    elif not qoq_ok:
        return 40, "PROMOTER_HOLDING_DECLINING", f"{note} — declining QoQ ✗"
    elif latest >= 50:
        return 90, None, f"{note} — high and stable ✓"
    elif latest >= 35:
        return 70, None, f"{note} — adequate ✓"
    else:
        return 40, "LOW_PROMOTER_HOLDING", f"{note} — low (<35%) ✗"


# ── Snapshot scorers ──────────────────────────────────────────────────────────

def _score_roe(roe):
    if roe is None: return 50
    if roe >= 25:   return 100
    if roe >= 15:   return 70
    if roe >= 10:   return 40
    return 10

def _score_debt(de, flags):
    if de is None: return 50
    if de > 2.0:
        flags.append("HIGH_DEBT")
        return 0
    if de <= 0:    return 100
    if de <= 0.5:  return 85
    if de <= 1.0:  return 55
    return 25

def _score_margin(m):
    if m is None: return 50
    if m >= 20:   return 100
    if m >= 12:   return 75
    if m >= 8:    return 50
    if m >= 3:    return 25
    return 0

def _score_growth(g):
    if g is None: return 50
    if g >= 20:   return 100
    if g >= 10:   return 75
    if g >= 5:    return 45
    if g >= 0:    return 20
    return 0


# ── Helpers ───────────────────────────────────────────────────────────────────

def _pct(val) -> Optional[float]:
    if val is None: return None
    try:   return round(float(val) * 100, 1)
    except: return None

def _de(val) -> Optional[float]:
    """Yahoo returns D/E * 100 — normalise to ratio."""
    if val is None: return None
    try:   return round(float(val) / 100.0, 2)
    except: return None

def _to_crores_list(series) -> List[float]:
    """Convert a pandas Series (newest-first) to chronological list in crores."""
    try:
        vals = series.dropna().tolist()[::-1]   # reverse to oldest-first
        return [round(v / 1e7, 1) for v in vals]  # INR → crores
    except Exception:
        return []


if __name__ == "__main__":
    import json
    result = fetch_fundamentals("TECHM")
    # Remove series arrays for clean display
    display = {k: v for k, v in result.items()
               if k not in ("revenue_series", "debt_series", "ocf_series",
                             "capex_series", "promoter_series")}
    print(json.dumps(display, indent=2))
    print("\nTrend notes:")
    for note in result.get("trend_notes", []):
        print(f"  {note}")
    print("\nFlags:", result.get("flags", []))