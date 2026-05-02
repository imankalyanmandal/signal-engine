"""
Layer 2 — LLM-Powered Composite Scorer

Uses llm_client.py which tries Gemini first, falls back to Groq automatically.
No changes needed here when switching providers — all routing is in llm_client.
"""

import json
from typing import Optional

from claude_config  import is_mock
from llm_client     import call_llm
from mock_responses import mock_composite_response

PASS_THRESHOLD = 55

COMPANY_NAMES = {
    "TECHM":      "Tech Mahindra",       "POWERGRID":  "Power Grid Corporation",
    "HDFCBANK":   "HDFC Bank",           "DIVISLAB":   "Divi's Laboratories",
    "TITAN":      "Titan Company",       "COALINDIA":  "Coal India",
    "SBIN":       "State Bank of India", "HCLTECH":    "HCL Technologies",
    "HINDALCO":   "Hindalco Industries", "INFY":       "Infosys",
    "AXISBANK":   "Axis Bank",           "ICICIBANK":  "ICICI Bank",
    "RELIANCE":   "Reliance Industries", "TCS":        "Tata Consultancy Services",
    "WIPRO":      "Wipro",               "BAJFINANCE": "Bajaj Finance",
    "KOTAKBANK":  "Kotak Mahindra Bank", "ADANIPORTS": "Adani Ports",
    "TATAMOTORS": "Tata Motors",         "MARUTI":     "Maruti Suzuki",
    "SUNPHARMA":  "Sun Pharmaceutical",  "DRREDDY":    "Dr Reddy's Laboratories",
    "CIPLA":      "Cipla",               "HEROMOTOCO": "Hero MotoCorp",
    "BAJAJFINSV": "Bajaj Finserv",       "NESTLEIND":  "Nestlé India",
    "BRITANNIA":  "Britannia Industries","JSWSTEEL":   "JSW Steel",
    "TATASTEEL":  "Tata Steel",          "NTPC":       "NTPC",
    "ONGC":       "ONGC",                "BPCL":       "Bharat Petroleum",
    "LT":         "Larsen & Toubro",     "EICHERMOT":  "Eicher Motors",
    "ASIANPAINT": "Asian Paints",        "ULTRACEMCO": "UltraTech Cement",
    "GRASIM":     "Grasim Industries",   "APOLLOHOSP": "Apollo Hospitals",
    "INDUSINDBK": "IndusInd Bank",       "ITC":        "ITC Limited",
    "BHARTIARTL": "Bharti Airtel",       "HDFCLIFE":   "HDFC Life Insurance",
    "SBILIFE":    "SBI Life Insurance",  "SHRIRAMFIN": "Shriram Finance",
    "BAJAJ-AUTO": "Bajaj Auto",          "ADANIENT":   "Adani Enterprises",
    "TATACONSUM": "Tata Consumer Products","HINDUNILVR":"Hindustan Unilever",
    "MM":         "Mahindra & Mahindra", "ZOMATO":     "Zomato",
}


def compute_composite_score(
    symbol: str,
    fundamental_result: dict,
    sentiment_result: dict,
    concall_result: dict,
    layer1_result: Optional[dict] = None,
) -> dict:

    company    = COMPANY_NAMES.get(symbol, symbol)
    fund_score = _safe_score(fundamental_result, "fundamental_score")
    sent_score = _safe_score(sentiment_result,   "sentiment_score")
    conc_score = _safe_score(concall_result,      "concall_score")

    if is_mock():
        print(f"  [{symbol}] MOCK_MODE — skipping LLM call")
        verdict = mock_composite_response(symbol, fund_score, sent_score, conc_score)
    else:
        context = _build_context(symbol, company, fundamental_result,
                                 sentiment_result, concall_result, layer1_result)
        verdict = _call_llm_and_parse(symbol, company, context,
                                       fund_score, sent_score, conc_score)

    return _format_result(symbol, company, verdict,
                          fundamental_result, sentiment_result, concall_result)


def get_company_name(symbol: str) -> str:
    return COMPANY_NAMES.get(symbol, symbol)


# ── Private helpers ───────────────────────────────────────────────────────────

def _call_llm_and_parse(symbol, company, context, fund_score, sent_score, conc_score) -> dict:
    """Call LLM via the provider chain and parse JSON response."""

    prompt = _build_prompt(company, symbol, context)
    raw    = call_llm(prompt, max_tokens=1500)

    if raw is None:
        print(f"  [{symbol}] All LLM providers failed — using fallback")
        return _fallback_verdict(symbol)

    try:
        from json_utils import extract_json
        parsed = extract_json(raw)
        parsed["layer2_pass"]    = parsed.get("composite_score", 0) >= PASS_THRESHOLD
        parsed["pass_threshold"] = PASS_THRESHOLD
        return parsed

    except (json.JSONDecodeError, ValueError) as e:
        print(f"  [{symbol}] JSON parse error: {e} — using fallback")
        print(f"  [{symbol}] Raw response: {raw[:200]}")
        return _fallback_verdict(symbol)


def _build_prompt(company: str, symbol: str, context: str) -> str:
    return f"""You are a senior swing trading analyst at an Indian equity fund.
Evaluate {company} ({symbol}) for a 5–20 day NSE swing trade.

{context}

Analyse ALL signals together. Look for:
1. Signal agreement or contradiction (e.g. strong ROE but guidance lowered)
2. Which signals matter most RIGHT NOW for a 5-20 day swing trade
3. Hard red flags that override an otherwise good score
4. Risk/reward for entering now vs waiting

Respond with ONLY a valid JSON object, no other text:

{{
  "composite_score": <integer 0-100>,
  "layer2_pass": <true if score >= 55>,
  "conviction": "<HIGH|MEDIUM|LOW>",
  "signal_alignment": "<ALIGNED|MIXED|CONFLICTED>",
  "swing_verdict": "<STRONG_BUY|BUY|HOLD|AVOID|STRONG_AVOID>",
  "rationale": "<3-4 sentences. Mention actual numbers. Flag any signal contradictions.>",
  "key_positives": ["<up to 3 specific reasons to enter>"],
  "key_risks": ["<up to 3 specific risks that could stop out the trade>"],
  "optimal_entry_timing": "<NOW|WAIT_FOR_PULLBACK|WAIT_FOR_CATALYST|NOT_NOW>",
  "entry_note": "<one sentence on when/how to enter if BUY or STRONG_BUY, else empty string>",
  "red_flags": ["<hard stop reasons — empty array if none>"],
  "fundamental_weight_used": <integer 0-100>,
  "sentiment_weight_used": <integer 0-100>,
  "concall_weight_used": <integer, these three must sum to 100>
}}"""


def _build_context(symbol, company, fund, sent, conc, l1) -> str:
    lines = []

    lines.append("=== FUNDAMENTALS (screener.in) ===")
    if fund.get("error"):
        lines.append(f"  Fetch failed: {fund.get('error_message', 'unknown')}")
    else:
        def f(val, sfx=""):
            return f"{val:.1f}{sfx}" if val is not None else "N/A"
        lines += [
            f"  ROE:                  {f(fund.get('roe'), '%')}",
            f"  Debt/Equity:          {f(fund.get('debt_equity'), 'x')}",
            f"  Promoter holding:     {f(fund.get('promoter_holding'), '%')}",
            f"  Revenue growth (3yr): {f(fund.get('revenue_growth_3y'), '% CAGR')}",
            f"  Profit growth (3yr):  {f(fund.get('profit_growth_3y'), '% CAGR')}",
            f"  Risk flags:           {', '.join(fund.get('flags', [])) or 'none'}",
        ]

    lines.append("\n=== NEWS SENTIMENT (last 30 days) ===")
    if sent.get("headlines_found", 0) == 0:
        lines.append("  No recent news found.")
    else:
        lines += [
            f"  Direction:    {sent.get('direction')} (confidence: {sent.get('confidence')})",
            f"  Swing impact: {sent.get('swing_impact')}",
            f"  Narrative:    {sent.get('narrative', '')}",
        ]
        for h in sent.get("headlines", [])[:5]:
            lines.append(f"    • {h}")

    lines.append("\n=== CONCALL ANALYSIS ===")
    if not conc.get("filing_date"):
        lines.append("  No recent transcript found.")
    else:
        lines += [
            f"  Date:            {conc.get('filing_date')}",
            f"  Management tone: {conc.get('tone')}",
            f"  Guidance:        {conc.get('guidance_change')}",
            f"  Summary:         {conc.get('summary', '')}",
        ]

    if l1:
        lines.append("\n=== LAYER 1 BACKTEST (5-year) ===")
        lines += [
            f"  Return:       {l1.get('returnPercent', 0):.1f}%",
            f"  Sharpe:       {l1.get('sharpeRatio', 0):.2f}",
            f"  Win rate:     {l1.get('winRate', 0):.0f}%",
            f"  Trades:       {l1.get('totalTrades', 0)}",
            f"  Max drawdown: {l1.get('maxDrawdown', 0):.1f}%",
        ]

    return "\n".join(lines)


def _safe_score(result: dict, key: str) -> float:
    if result.get("error"):
        return 50.0
    try:
        return float(result.get(key, 50))
    except (TypeError, ValueError):
        return 50.0


def _fallback_verdict(symbol: str) -> dict:
    return {
        "composite_score":        50,
        "layer2_pass":            False,
        "conviction":             "LOW",
        "signal_alignment":       "MIXED",
        "swing_verdict":          "HOLD",
        "rationale":              f"LLM analysis unavailable for {symbol}. Manual review required.",
        "key_positives":          [],
        "key_risks":              ["LLM unavailable — check API keys in .env"],
        "optimal_entry_timing":   "NOT_NOW",
        "entry_note":             "",
        "red_flags":              ["LLM_UNAVAILABLE"],
        "fundamental_weight_used":50,
        "sentiment_weight_used":  30,
        "concall_weight_used":    20,
        "pass_threshold":         PASS_THRESHOLD,
        "claude_fallback":        True,
    }


def _format_result(symbol, company, verdict, fund, sent, conc) -> dict:
    return {
        "symbol":               symbol,
        "company_name":         company,
        "layer2_pass":          verdict.get("layer2_pass", False),
        "composite_score":      verdict.get("composite_score", 0),
        "pass_threshold":       PASS_THRESHOLD,
        "conviction":           verdict.get("conviction", "LOW"),
        "signal_alignment":     verdict.get("signal_alignment", "MIXED"),
        "swing_verdict":        verdict.get("swing_verdict", "HOLD"),
        "rationale":            verdict.get("rationale", ""),
        "key_positives":        verdict.get("key_positives", []),
        "key_risks":            verdict.get("key_risks", []),
        "optimal_entry_timing": verdict.get("optimal_entry_timing", "NOT_NOW"),
        "entry_note":           verdict.get("entry_note", ""),
        "red_flags":            verdict.get("red_flags", []),
        "weights_used": {
            "fundamentals": verdict.get("fundamental_weight_used", 50),
            "sentiment":    verdict.get("sentiment_weight_used",    30),
            "concall":      verdict.get("concall_weight_used",      20),
        },
        "fundamentals": {
            "roe":              fund.get("roe"),
            "debt_equity":      fund.get("debt_equity"),
            "promoter_holding": fund.get("promoter_holding"),
            "revenue_growth_3y":fund.get("revenue_growth_3y"),
            "profit_growth_3y": fund.get("profit_growth_3y"),
            "flags":            fund.get("flags", []),
        },
        "sentiment": {
            "direction":    sent.get("direction", "NEUTRAL"),
            "confidence":   sent.get("confidence", "LOW"),
            "narrative":    sent.get("narrative", ""),
            "top_headlines":sent.get("headlines", [])[:3],
        },
        "concall": {
            "tone":            conc.get("tone", "CAUTIOUS"),
            "guidance_change": conc.get("guidance_change", "NOT_GIVEN"),
            "swing_signal":    conc.get("swing_signal", "NEUTRAL"),
            "summary":         conc.get("summary", ""),
            "filing_date":     conc.get("filing_date"),
        },
        "claude_fallback": verdict.get("claude_fallback", False),
        "mock":            verdict.get("mock", False),
    }