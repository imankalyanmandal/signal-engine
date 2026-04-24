"""
Symbol provider — fetches live NSE index constituents.

Handles two known Yahoo Finance quirks for Indian stocks:
  1. Symbols with '&' (M&M, M&MFIN, GVT&D) → strip '&' for Yahoo ticker
  2. Index name itself sometimes appears in constituent list → filtered out
"""

import requests

# ── Yahoo Finance ticker exceptions ───────────────────────────────────────────
# Symbols that need special mapping for Yahoo Finance
# Format: NSE_SYMBOL → Yahoo_suffix (without .NS)
YAHOO_EXCEPTIONS = {
    "M&M":       "MM",
    "M&MFIN":    "MMFIN",
    "GVT&D":     "GVTD",
    "L&TFH":     "L&TFH",     # works as-is with URL encoding
    "BAJAJ-AUTO":"BAJAJ-AUTO", # works as-is
}

# ── Index name patterns to filter from constituent lists ──────────────────────
# NSE sometimes returns the index name as a symbol in the list
INDEX_KEYWORDS = {
    "NIFTY", "SENSEX", "MIDCAP", "SMALLCAP", "LARGECAP",
    "NEXT50", "NEXT 50", "NIFTY50", "NIFTY 50",
}

# ── NSE index constituent URLs ────────────────────────────────────────────────
INDEX_URLS = {
    "NIFTY 50":      "https://archives.nseindia.com/content/indices/ind_nifty50list.csv",
    "NIFTY 100":     "https://archives.nseindia.com/content/indices/ind_nifty100list.csv",
    "NIFTY 200":     "https://archives.nseindia.com/content/indices/ind_nifty200list.csv",
    "NIFTY NEXT 50": "https://archives.nseindia.com/content/indices/ind_niftynext50list.csv",
}

# ── Hardcoded fallback lists ───────────────────────────────────────────────────
NIFTY_50_FALLBACK = [
    "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
    "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BPCL", "BHARTIARTL",
    "BRITANNIA", "CIPLA", "COALINDIA", "DIVISLAB", "DRREDDY",
    "EICHERMOT", "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE",
    "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK", "ITC",
    "INDUSINDBK", "INFY", "JSWSTEEL", "KOTAKBANK", "LT",
    "M&M", "MARUTI", "NESTLEIND", "NTPC", "ONGC",
    "POWERGRID", "RELIANCE", "SBILIFE", "SHRIRAMFIN", "SBIN",
    "SUNPHARMA", "TCS", "TATACONSUM", "TATAMOTORS", "TATASTEEL",
    "TECHM", "TITAN", "ULTRACEMCO", "WIPRO", "ZOMATO",
]


def get_symbols(index: str = "NIFTY 50") -> list:
    """
    Returns NSE symbols for the given index.
    Tries live NSE CSV first, falls back to hardcoded list.

    Filters out:
    - Index name tokens (e.g. "NIFTY 200" appearing as a symbol)
    - Duplicates
    - Non-stock entries
    """
    index = index.upper().strip()
    url   = INDEX_URLS.get(index)

    if url:
        symbols = _fetch_from_nse(url)
        if symbols:
            return _clean(symbols)

    # Fallback for Nifty 50
    if index in ("NIFTY 50", "NIFTY50"):
        print(f"  [symbol_provider] Using fallback list for {index}")
        return _clean(NIFTY_50_FALLBACK)

    # For other indices, return empty — caller handles error
    print(f"  [symbol_provider] No data for index: {index}")
    return []


def to_yahoo_ticker(symbol: str, exchange: str = "NS") -> str:
    """
    Converts an NSE symbol to a Yahoo Finance ticker.

    Examples:
      HDFCBANK  → HDFCBANK.NS
      M&M       → MM.NS         (& stripped — Yahoo doesn't support it)
      BAJAJ-AUTO→ BAJAJ-AUTO.NS (hyphen works fine)
      GVT&D     → GVTD.NS
    """
    symbol = symbol.upper().strip()

    # Check exception map first
    if symbol in YAHOO_EXCEPTIONS:
        return f"{YAHOO_EXCEPTIONS[symbol]}.{exchange}"

    # Strip & for Yahoo Finance (they don't support it in tickers)
    yahoo_sym = symbol.replace("&", "")
    return f"{yahoo_sym}.{exchange}"


# ── Private helpers ───────────────────────────────────────────────────────────

def _fetch_from_nse(url: str) -> list:
    """Fetch constituent list from NSE CSV endpoint."""
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept":     "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer":    "https://www.nseindia.com/",
    }
    try:
        r = requests.get(url, headers=headers, timeout=8)
        if r.status_code != 200:
            print(f"  [symbol_provider] NSE returned {r.status_code} for {url}")
            return []

        lines   = r.text.strip().split("\n")
        symbols = []

        # NSE CSV columns: Company Name, Industry, Symbol, Series, ISIN Code
        # Column 0 = company name  e.g. "Adani Enterprises Limited"
        # Column 2 = NSE symbol    e.g. "ADANIENT"  ← this is what we need
        for line in lines[1:]:
            line = line.strip()
            if not line:
                continue
            parts = line.split(",")
            if len(parts) < 3:
                continue
            symbol = parts[2].strip().strip('"').upper()
            if symbol:
                symbols.append(symbol)

        print(f"  [symbol_provider] Fetched {len(symbols)} symbols from NSE")
        return symbols

    except Exception as e:
        print(f"  [symbol_provider] NSE fetch error: {e}")
        return []


def _clean(symbols: list) -> list:
    """
    Remove index names, duplicates, and invalid entries from symbol list.

    Key fix: NSE sometimes includes the index name (e.g. 'NIFTY 200')
    as the first entry in the CSV constituent list. This filters those out.
    """
    seen   = set()
    result = []

    for sym in symbols:
        sym = sym.strip().upper()

        # Skip empty
        if not sym:
            continue

        # Skip index name entries (e.g. "NIFTY 200", "NIFTY 50")
        if _is_index_name(sym):
            print(f"  [symbol_provider] Filtered index token: {sym}")
            continue

        # Skip duplicates
        if sym in seen:
            continue

        seen.add(sym)
        result.append(sym)

    return result


def _is_index_name(sym: str) -> bool:
    """
    Returns True if the symbol looks like an index name rather than a stock.

    Catches:
      "NIFTY 200"  → True
      "NIFTY50"    → True
      "HDFCBANK"   → False
      "M&M"        → False
    """
    # Direct match
    if sym in INDEX_KEYWORDS:
        return True

    # Starts with NIFTY or SENSEX followed by space or digits
    for kw in ("NIFTY", "SENSEX"):
        if sym.startswith(kw) and (len(sym) == len(kw) or
                                    sym[len(kw)] in " 0123456789"):
            return True

    # Contains a space and starts with index keyword (e.g. "NIFTY 200")
    if " " in sym:
        first_word = sym.split()[0]
        if first_word in ("NIFTY", "SENSEX", "MIDCAP", "SMALLCAP"):
            return True

    return False