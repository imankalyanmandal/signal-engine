"""
mock_responses.py — Realistic fake Claude responses for testing.

Used when MOCK_MODE = True in claude_config.py.
Simulates what Claude would return for sentiment, concall, and composite
scoring so you can verify the full pipeline without API charges.

Responses are varied by symbol so different stocks give different results,
making it easy to check that ranking and pass/fail logic works correctly.
"""

import hashlib


def mock_sentiment_response(symbol: str, headlines: list) -> dict:
    """
    Returns a plausible sentiment result without calling Claude.
    Varies by symbol so stocks rank differently.
    """
    seed   = int(hashlib.md5(symbol.encode()).hexdigest(), 16) % 100
    score  = 45 + (seed % 40)   # 45–84
    direction = "BULLISH" if score >= 60 else ("BEARISH" if score < 50 else "NEUTRAL")
    confidence = "MEDIUM" if 50 <= score <= 75 else "LOW"

    return {
        "sentiment_score": score,
        "direction":       direction,
        "confidence":      confidence,
        "key_positive":    [f"{symbol} showed strong institutional buying", "Sector tailwinds positive"],
        "key_negative":    ["Global macro uncertainty", "FII outflows in recent sessions"],
        "narrative":       f"Mock sentiment for {symbol}: score {score} — {direction.lower()} bias based on simulated news analysis.",
        "swing_impact":    "SHORT_TERM_POSITIVE" if score >= 60 else "SHORT_TERM_NEUTRAL",
    }


def mock_concall_response(symbol: str) -> dict:
    """
    Returns a plausible concall analysis result without calling Claude.
    """
    seed  = int(hashlib.md5((symbol + "concall").encode()).hexdigest(), 16) % 100
    score = 40 + (seed % 45)   # 40–84

    tone      = "CONFIDENT" if score >= 65 else ("CAUTIOUS" if score >= 50 else "CONCERNED")
    guidance  = "MAINTAINED" if score >= 55 else ("RAISED" if score >= 70 else "LOWERED")
    signal    = "BUY_BIAS" if score >= 65 else ("SELL_BIAS" if score < 45 else "NEUTRAL")

    return {
        "concall_score":   score,
        "tone":            tone,
        "guidance_change": guidance,
        "key_positives":   [f"Management confident on {symbol} margin expansion", "Deal pipeline robust"],
        "key_negatives":   ["Input cost pressure mentioned", "Currency headwinds flagged"],
        "summary":         f"Mock concall for {symbol}: Management tone is {tone.lower()}, guidance {guidance.lower()}. Score {score}/100.",
        "swing_signal":    signal,
        "confidence":      "MEDIUM",
    }


def mock_composite_response(symbol: str, fund_score: float, sent_score: float, conc_score: float) -> dict:
    """
    Returns a plausible holistic Claude verdict without calling Claude.
    Uses the real sub-scores so the composite is meaningful.
    """
    # Weighted blend (same as what real Claude would do roughly)
    composite = round(fund_score * 0.50 + sent_score * 0.30 + conc_score * 0.20, 1)
    passes    = composite >= 55

    conviction = "HIGH" if composite >= 70 else ("MEDIUM" if composite >= 55 else "LOW")
    verdict    = ("STRONG_BUY" if composite >= 75 else
                  "BUY"        if composite >= 60 else
                  "HOLD"       if composite >= 45 else
                  "AVOID")

    alignment = ("ALIGNED"    if abs(fund_score - sent_score) < 15 and abs(sent_score - conc_score) < 15
                 else "MIXED" if abs(fund_score - sent_score) < 25
                 else "CONFLICTED")

    timing = ("NOW"                if composite >= 70 else
              "WAIT_FOR_PULLBACK"  if composite >= 60 else
              "WAIT_FOR_CATALYST" if composite >= 50 else
              "NOT_NOW")

    return {
        "composite_score":          composite,
        "layer2_pass":              passes,
        "conviction":               conviction,
        "signal_alignment":         alignment,
        "swing_verdict":            verdict,
        "rationale":                (
            f"[MOCK] {symbol} composite score {composite}/100. "
            f"Fundamentals ({fund_score:.0f}), sentiment ({sent_score:.0f}), "
            f"concall ({conc_score:.0f}) are {alignment.lower()}. "
            f"Signal engine recommends {verdict} with {conviction.lower()} conviction. "
            f"Replace MOCK_MODE=True with False and add API key to get real Claude analysis."
        ),
        "key_positives":            [f"Fundamental score {fund_score:.0f}/100 supports entry",
                                     f"Sentiment {sent_score:.0f}/100 indicates {('positive' if sent_score >= 55 else 'neutral')} market view"],
        "key_risks":                [f"Concall score {conc_score:.0f}/100 — management tone warrants monitoring",
                                     "Mock mode — real risks not evaluated"],
        "optimal_entry_timing":     timing,
        "entry_note":               f"Mock entry note for {symbol}. Enable real Claude for actionable guidance.",
        "red_flags":                ["MOCK_MODE_ACTIVE"],
        "fundamental_weight_used":  50,
        "sentiment_weight_used":    30,
        "concall_weight_used":      20,
        "pass_threshold":           55,
        "claude_fallback":          False,
        "mock":                     True,
    }
