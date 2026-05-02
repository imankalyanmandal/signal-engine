"""
Concall Analyser — fetches and analyses earnings call transcripts for Indian stocks.

Source priority:
  1. BSE XML API (most reliable, structured data)
  2. NSE announcements API
  3. Screener.in concall notes (text-based, good coverage)
  4. Google News search for concall summaries
  5. Neutral fallback (score=50) if all sources fail

The BSE XML API issue in the previous version:
  - Was using an incorrect endpoint that returns HTML investor pages
  - Correct endpoint: https://api.bseindia.com/BseIndiaAPI/api/AnnGetAnnouncementXml/w
  - Category code 30 = Investor Presentations / Concall transcripts
  - Category code 10 = Financial Results
"""

import re
import time
import requests
from io import BytesIO
from mock_responses import mock_concall_response
from claude_config  import is_mock
from llm_client     import call_llm

try:
    from pdfminer.high_level import extract_text as pdf_extract_text
    PDF_AVAILABLE = True
except ImportError:
    PDF_AVAILABLE = False

# ── BSE scrip codes for Nifty 50 + common Nifty 100 stocks ───────────────────
BSE_CODES = {
    "ADANIENT":   "512599", "ADANIPORTS":  "532921", "APOLLOHOSP":  "508869",
    "ASIANPAINT": "500820", "AXISBANK":    "532215", "BAJAJ-AUTO":  "532977",
    "BAJFINANCE": "500034", "BAJAJFINSV":  "532978", "BPCL":        "500547",
    "BHARTIARTL": "532454", "BRITANNIA":   "500825", "CIPLA":       "500087",
    "COALINDIA":  "533278", "DIVISLAB":    "532488", "DRREDDY":     "500124",
    "EICHERMOT":  "505200", "GRASIM":      "500300", "HCLTECH":     "532281",
    "HDFCBANK":   "500180", "HDFCLIFE":    "540777", "HEROMOTOCO":  "500182",
    "HINDALCO":   "500440", "HINDUNILVR":  "500696", "ICICIBANK":   "532174",
    "ITC":        "500875", "INDUSINDBK":  "532187", "INFY":        "500209",
    "JSWSTEEL":   "500228", "KOTAKBANK":   "500247", "LT":          "500510",
    "M&M":        "500520", "MARUTI":      "532500", "NESTLEIND":   "500790",
    "NTPC":       "532555", "ONGC":        "500312", "POWERGRID":   "532898",
    "RELIANCE":   "500325", "SBILIFE":     "540719", "SHRIRAMFIN":  "511218",
    "SBIN":       "500112", "SUNPHARMA":   "524715", "TCS":         "532540",
    "TATACONSUM": "500800", "TATAMOTORS":  "500570", "TATASTEEL":   "500470",
    "TECHM":      "532755", "TITAN":       "500114", "ULTRACEMCO":  "532538",
    "WIPRO":      "507685", "ZOMATO":      "543320",
    # Nifty Next 50 additions
    "PIDILITIND":  "500331", "SIEMENS":    "500550", "HAVELLS":     "517354",
    "MARICO":      "531642", "DABUR":      "500096", "BERGEPAINT":  "509480",
    "GODREJCP":    "532424", "TRENT":      "500251", "DMART":       "540376",
    "NYKAA":       "543384", "POLICYBZR":  "543390", "ZEEL":        "505537",
    "CHOLAFIN":    "500124", "MUTHOOTFIN": "533398", "BANDHANBNK":  "541153",
    "AUBANK":      "540611", "IDFCFIRSTB": "539437", "FEDERALBNK":  "500469",
    "LICI":        "543526", "ICICIGI":    "540716",
}

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept":     "application/json, text/plain, */*",
    "Referer":    "https://www.bseindia.com",
}

TIMEOUT = 10


def fetch_concall_analysis(symbol: str, company_name: str) -> dict:
    """
    Main entry point. Returns a dict with:
      tone, guidance_change, swing_signal, summary, filing_date, source
    """
    if is_mock():
        return mock_concall_response(symbol)

    print(f"  [{symbol}] Fetching concall transcript...")

    # Try each source in order
    transcript, filing_date, source = None, None, None

    # Source 1: BSE XML API (best structured data)
    result = _fetch_bse_concall(symbol)
    if result:
        transcript, filing_date, source = result

    # Source 2: NSE announcements
    if not transcript:
        result = _fetch_nse_concall(symbol)
        if result:
            transcript, filing_date, source = result

    # Source 3: Screener.in concall notes
    if not transcript:
        result = _fetch_screener_concall(symbol, company_name)
        if result:
            transcript, filing_date, source = result

    # Source 4: Google News for concall summaries
    if not transcript:
        result = _fetch_news_concall(symbol, company_name)
        if result:
            transcript, filing_date, source = result

    if not transcript:
        print(f"  [{symbol}] No concall data found — returning neutral")
        return _neutral_response(symbol)

    print(f"  [{symbol}] Analysing concall ({source}, {len(transcript)} chars)...")
    return _analyse_with_llm(symbol, company_name, transcript, filing_date, source)


# ── Source 1: BSE XML API ─────────────────────────────────────────────────────

def _fetch_bse_concall(symbol: str):
    """
    BSE announcements API — returns PDFs of investor presentations and concall docs.
    Category 30 = Investor Presentations (includes concall transcripts)
    Category 10 = Financial Results (includes concall audio/transcript refs)
    """
    scrip_code = BSE_CODES.get(symbol)
    if not scrip_code:
        return None

    for category in ["30", "10"]:
        try:
            url = (
                f"https://api.bseindia.com/BseIndiaAPI/api/AnnGetAnnouncementXml/w"
                f"?strCat={category}&strPrevDate=&strScrip={scrip_code}"
                f"&strSearch=P&strToDate=&strType=C&subcategory=-1"
            )
            r = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
            if r.status_code != 200:
                continue
            if 'application/json' not in r.headers.get('Content-Type', ''):
                continue
            try:
                data = r.json()
            except Exception:
                continue
            announcements = data.get("Table", [])

            for ann in announcements[:5]:   # check top 5 most recent
                headline = str(ann.get("NEWSSUB", "")).lower()
                dt       = ann.get("News_submission_dt", "")

                # Look for concall-related announcements
                if any(kw in headline for kw in
                       ["concall", "earnings call", "investor", "analyst", "transcript",
                        "presentation", "conference call", "q1", "q2", "q3", "q4"]):

                    attachment = ann.get("ATTACHMENTNAME", "")
                    if attachment:
                        pdf_url = f"https://www.bseindia.com/xml-data/corpfiling/AttachHis/{attachment}"
                        text    = _extract_pdf_text(pdf_url)
                        if text and len(text) > 200:
                            return text[:8000], dt, "BSE_PDF"

                    # No PDF — use headline as context
                    if len(headline) > 30:
                        return headline, dt, "BSE_HEADLINE"

        except Exception as e:
            print(f"  [{symbol}] BSE API error (cat {category}): {e}")

    return None


# ── Source 2: NSE announcements ───────────────────────────────────────────────

def _fetch_nse_concall(symbol: str):
    """NSE corporate announcements — returns JSON list of filings."""
    try:
        url = f"https://www.nseindia.com/api/corp-announcements?index=equities&symbol={symbol}"
        r   = requests.get(url, headers={**HEADERS, "Referer": "https://www.nseindia.com"}, timeout=TIMEOUT)
        if r.status_code != 200:
            return None

        items = r.json()
        if not isinstance(items, list):
            return None

        for item in items[:10]:
            subject = str(item.get("subject", "")).lower()
            dt      = item.get("bm_timestamp", "")
            if any(kw in subject for kw in ["concall", "conference call", "earnings call",
                                             "investor", "analyst", "presentation"]):
                # Try to fetch attachment
                attach = item.get("attchmntFile", "")
                if attach:
                    pdf_url = f"https://www.nseindia.com{attach}"
                    text    = _extract_pdf_text(pdf_url)
                    if text and len(text) > 200:
                        return text[:8000], dt, "NSE_PDF"

                if len(subject) > 20:
                    return subject, dt, "NSE_HEADLINE"

    except Exception as e:
        print(f"  NSE concall error for {symbol}: {e}")

    return None


# ── Source 3: Screener.in concall notes ───────────────────────────────────────

def _fetch_screener_concall(symbol: str, company_name: str):
    """
    Screener.in has concall notes in the company page under the 'Concalls' section.
    The API endpoint returns structured data including concall notes.
    """
    try:
        # Screener uses company slug (lowercase, hyphens)
        slug = company_name.lower().replace(" ", "-").replace(".", "").replace("&", "and")

        # Try direct symbol first, then company name slug
        for query in [symbol.lower(), slug]:
            url = f"https://www.screener.in/company/{query}/concalls/"
            r   = requests.get(url, headers=HEADERS, timeout=TIMEOUT)

            if r.status_code == 200 and "concall" in r.text.lower():
                # Extract text content (simple parse — screener renders server-side)
                text = re.sub(r'<[^>]+>', ' ', r.text)  # strip HTML
                text = re.sub(r'\s+', ' ', text)

                # Find the concall section
                idx = text.lower().find("concall")
                if idx >= 0:
                    snippet = text[max(0, idx-100):idx+3000]
                    if len(snippet) > 200:
                        return snippet, None, "SCREENER"

    except Exception as e:
        print(f"  Screener concall error for {symbol}: {e}")

    return None


# ── Source 4: Google News for concall summaries ───────────────────────────────

def _fetch_news_concall(symbol: str, company_name: str):
    """
    Search Google News RSS for recent concall summaries / analyst notes.
    Better coverage than BSE for stocks where PDFs aren't uploading properly.
    """
    try:
        queries = [
            f"{company_name} concall Q4 2025",
            f"{symbol} earnings call transcript 2025",
            f"{company_name} quarterly results analyst commentary",
        ]

        for query in queries:
            encoded = requests.utils.quote(query)
            url     = f"https://news.google.com/rss/search?q={encoded}&hl=en-IN&gl=IN&ceid=IN:en"
            r       = requests.get(url, headers=HEADERS, timeout=TIMEOUT)

            if r.status_code != 200:
                continue

            # Parse RSS — extract titles and descriptions
            items = re.findall(r'<item>(.*?)</item>', r.text, re.DOTALL)
            snippets = []

            for item in items[:5]:
                title = re.search(r'<title>(.*?)</title>', item)
                desc  = re.search(r'<description>(.*?)</description>', item)
                pub   = re.search(r'<pubDate>(.*?)</pubDate>', item)

                if title:
                    text = title.group(1)
                    if desc:
                        text += " — " + re.sub(r'<[^>]+>', '', desc.group(1))
                    # Only keep concall-relevant news
                    if any(kw in text.lower() for kw in
                           ["result", "concall", "earnings", "revenue", "profit",
                            "guidance", "analyst", "quarterly"]):
                        snippets.append(text[:500])

            if snippets:
                combined = "\n\n".join(snippets)
                pub_date = None
                if items:
                    pd_match = re.search(r'<pubDate>(.*?)</pubDate>', items[0])
                    if pd_match:
                        pub_date = pd_match.group(1)
                return combined, pub_date, "GOOGLE_NEWS"

            time.sleep(0.5)

    except Exception as e:
        print(f"  News concall error for {symbol}: {e}")

    return None


# ── PDF extraction ─────────────────────────────────────────────────────────────

def _extract_pdf_text(url: str) -> str:
    """Download a PDF and extract text. Returns empty string on failure."""
    if not PDF_AVAILABLE:
        return ""
    try:
        r = requests.get(url, headers=HEADERS, timeout=15)
        if r.status_code != 200 or not r.content:
            return ""
        content_type = r.headers.get("Content-Type", "")
        if "pdf" not in content_type.lower() and not url.lower().endswith(".pdf"):
            return ""
        text = pdf_extract_text(BytesIO(r.content))
        return text.strip() if text else ""
    except Exception:
        return ""


# ── LLM analysis ──────────────────────────────────────────────────────────────

def _analyse_with_llm(symbol: str, company: str, transcript: str,
                       filing_date: str, source: str) -> dict:
    prompt = f"""You are a financial analyst specialising in Indian equities.
Analyse this {company} ({symbol}) earnings call / investor communication for swing trading signals.

SOURCE: {source}
DATE: {filing_date or 'recent'}

CONTENT:
{transcript}

Respond in this EXACT JSON format (no markdown, no extra text):
{{
  "tone": "CONFIDENT|CAUTIOUS|CONCERNED",
  "guidance_change": "RAISED|MAINTAINED|LOWERED|NOT_GIVEN",
  "swing_signal": "BUY_BIAS|HOLD_BIAS|SELL_BIAS|NEUTRAL",
  "summary": "2-3 sentence summary of key takeaways for a swing trader",
  "key_positives": ["positive 1", "positive 2"],
  "key_risks": ["risk 1", "risk 2"]
}}

Rules:
- tone: management's overall tone and confidence level
- guidance_change: whether they raised, maintained, or lowered forward guidance
- swing_signal: 5-20 day swing trade implication (BUY_BIAS = management bullish + strong numbers)
- Be concise and factual, not promotional
"""

    try:
        import json
        raw    = call_llm(prompt, max_tokens=1500)
        from json_utils import extract_json
        result = extract_json(raw)

        return {
            "tone":            result.get("tone",            "CAUTIOUS"),
            "guidance_change": result.get("guidance_change", "NOT_GIVEN"),
            "swing_signal":    result.get("swing_signal",    "NEUTRAL"),
            "summary":         result.get("summary",         "Analysis based on recent filings."),
            "key_positives":   result.get("key_positives",   []),
            "key_risks":       result.get("key_risks",       []),
            "filing_date":     filing_date,
            "source":          source,
        }
    except Exception as e:
        print(f"  [{symbol}] LLM concall analysis failed: {e}")
        return _neutral_response(symbol, source=source, filing_date=filing_date)


# ── Neutral fallback ───────────────────────────────────────────────────────────

def _neutral_response(symbol: str, source: str = None, filing_date: str = None) -> dict:
    return {
        "tone":            "CAUTIOUS",
        "guidance_change": "NOT_GIVEN",
        "swing_signal":    "NEUTRAL",
        "summary":         f"No recent concall transcript found for {symbol}. Sentiment treated as neutral.",
        "key_positives":   [],
        "key_risks":       [],
        "filing_date":     filing_date,
        "source":          source or "NONE",
    }