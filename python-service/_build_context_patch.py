"""
Patch for composite_scorer.py — replace the _build_context() function only.
Everything else in composite_scorer.py stays unchanged.

The updated context now includes:
  - Quarterly revenue/debt/OCF trend series (last 4 quarters)
  - Promoter holding series (last 8 quarters)
  - Trend notes (human-readable verdicts for each dimension)
  - Flags with their meaning explained

This gives the LLM direction information, not just snapshot values.
"""


def _build_context(symbol, company, fund, sent, conc, l1) -> str:
    lines = []

    # ── Snapshot fundamentals ─────────────────────────────────────────────────
    lines.append("=== FUNDAMENTALS (snapshot) ===")
    if fund.get("error"):
        lines.append(f"  Fetch failed: {fund.get('error_message', 'unknown')}")
    else:
        def f(val, sfx=""):
            return f"{val:.1f}{sfx}" if val is not None else "N/A"

        lines += [
            f"  ROE:              {f(fund.get('roe'), '%')}",
            f"  Debt/Equity:      {f(fund.get('debt_equity'), 'x')}",
            f"  Profit margin:    {f(fund.get('profit_margin'), '%')}",
            f"  Revenue growth:   {f(fund.get('revenue_growth_3y'), '% YoY')}",
            f"  Earnings growth:  {f(fund.get('profit_growth_3y'), '% YoY')}",
            f"  Promoter holding: {f(fund.get('promoter_holding'), '%')}",
            f"  FCF (latest qtr): {f(fund.get('fcf_latest_cr'), ' Cr')}",
        ]

    # ── Quarterly trends (the new data) ──────────────────────────────────────
    lines.append("\n=== QUARTERLY TRENDS (last 4 quarters, oldest → newest) ===")

    rev_series      = fund.get("revenue_series", [])
    debt_series     = fund.get("debt_series", [])
    ocf_series      = fund.get("ocf_series", [])
    promoter_series = fund.get("promoter_series", [])
    trend_notes     = fund.get("trend_notes", [])

    if rev_series:
        direction = "INCREASING ↑" if rev_series[-1] > rev_series[-2] else "DECLINING ↓"
        lines.append(f"  Revenue (Cr):          {rev_series} → {direction}")
    if debt_series:
        direction = "DECREASING ↓ (good)" if debt_series[-1] < debt_series[-2] else "INCREASING ↑ (caution)"
        lines.append(f"  Total debt (Cr):       {debt_series} → {direction}")
    if ocf_series:
        direction = "IMPROVING ↑" if ocf_series[-1] > ocf_series[-2] else "DECLINING ↓"
        lines.append(f"  Operating CF (Cr):     {ocf_series} → {direction}")
    if promoter_series:
        direction = "STABLE/INCREASING ✓" if promoter_series[-1] >= promoter_series[-2] else "DECLINING ✗"
        lines.append(f"  Promoter holding (%):  {promoter_series} → {direction}")

    if trend_notes:
        lines.append("\n  Trend verdicts:")
        for note in trend_notes:
            lines.append(f"    • {note}")

    # ── Risk flags ────────────────────────────────────────────────────────────
    flags = fund.get("flags", [])
    if flags:
        lines.append(f"\n  Active flags: {', '.join(flags)}")
        # Explain the most important flags
        flag_explanations = {
            "PROMOTER_SELLING_AFTER_ACCUMULATION":
                "  !! SERIOUS: Promoters accumulated for 2 years then started selling — insider exit signal",
            "REVENUE_DECLINING_QOQ_AND_YOY":
                "  !! Revenue is falling both quarter-on-quarter AND year-on-year — business momentum gone",
            "DEBT_INCREASING_QOQ_AND_YOY":
                "  !! Debt growing both QoQ and YoY — balance sheet deteriorating",
            "OCF_DECLINING":
                "  !! Operating cash flow falling — earnings quality at risk",
            "HIGH_DEBT":
                "  ! Debt/equity > 2x — leverage risk amplifies any downside",
        }
        for flag in flags:
            if flag in flag_explanations:
                lines.append(flag_explanations[flag])

    # ── News sentiment ────────────────────────────────────────────────────────
    lines.append("\n=== NEWS SENTIMENT (last 30 days) ===")
    if sent.get("headlines_found", 0) == 0:
        lines.append("  No recent news — sentiment treated as neutral.")
    else:
        lines += [
            f"  Direction:    {sent.get('direction')} (confidence: {sent.get('confidence')})",
            f"  Swing impact: {sent.get('swing_impact')}",
            f"  Narrative:    {sent.get('narrative', '')}",
        ]
        for h in sent.get("headlines", [])[:5]:
            lines.append(f"    • {h}")

    # ── Concall ───────────────────────────────────────────────────────────────
    lines.append("\n=== CONCALL ANALYSIS ===")
    if not conc.get("filing_date"):
        lines.append("  No recent transcript — treating concall as neutral (score 50).")
    else:
        lines += [
            f"  Date:            {conc.get('filing_date')}",
            f"  Management tone: {conc.get('tone')}",
            f"  Guidance:        {conc.get('guidance_change')}",
            f"  Summary:         {conc.get('summary', '')}",
        ]

    # ── Layer 1 backtest ──────────────────────────────────────────────────────
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
