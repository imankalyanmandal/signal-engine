"""
Layer 2 — News Sentiment Analyser (fixed version)

Problems fixed:
  1. Search term matching was too strict — now tries symbol, company name,
     and common abbreviations
  2. Added Google News RSS as primary source (more reliable than ET/MC)
  3. Added fallback: if no headlines found, returns neutral instead of failing

Sources tried in order:
  1. Google News RSS (symbol + NSE)
  2. Economic Times Markets RSS
  3. MoneyControl RSS
"""

import feedparser
import json
import urllib.parse
from datetime import datetime, timedelta
from typing import Optional

from claude_config  import is_mock
from llm_client     import call_llm
from mock_responses import mock_sentiment_response

# Company name abbreviations commonly used in news
COMPANY_ALIASES = {
    "TECHM":      ["Tech Mahindra", "TechM", "TECHM"],
    "HDFCBANK":   ["HDFC Bank", "HDFC"],
    "POWERGRID":  ["Power Grid", "PGCIL", "PowerGrid"],
    "DIVISLAB":   ["Divi's Lab", "Divis Lab", "DIVISLAB"],
    "COALINDIA":  ["Coal India", "CIL"],
    "SBIN":       ["SBI", "State Bank"],
    "HCLTECH":    ["HCL Tech", "HCL Technologies", "HCL"],
    "INFY":       ["Infosys", "Infy"],
    "HINDALCO":   ["Hindalco"],
    "AXISBANK":   ["Axis Bank"],
    "ICICIBANK":  ["ICICI Bank", "ICICI"],
    "RELIANCE":   ["Reliance", "RIL"],
    "TCS":        ["TCS", "Tata Consultancy"],
    "WIPRO":      ["Wipro"],
    "TITAN":      ["Titan"],
    "KOTAKBANK":  ["Kotak Bank", "Kotak Mahindra"],
    "BAJFINANCE": ["Bajaj Finance"],
    "MARUTI":     ["Maruti", "Maruti Suzuki", "MSIL"],
    "SUNPHARMA":  ["Sun Pharma", "Sun Pharmaceutical"],
}

RSS_FEEDS = [
    # Google News RSS — most reliable for Indian stocks
    "https://news.google.com/rss/search?q={query}+NSE+stock&hl=en-IN&gl=IN&ceid=IN:en",
    # Economic Times
    "https://economictimes.indiatimes.com/markets/rss.cms",
    # MoneyControl
    "https://www.moneycontrol.com/rss/marketoutlook.xml",
]


def fetch_sentiment(symbol: str, company_name: str = "") -> dict:
    if is_mock():
        result = mock_sentiment_response(symbol, [])
        return {"symbol": symbol, "source": "mock", "error": False,
                "headlines_found": 0, **result, "headlines": []}

    aliases  = COMPANY_ALIASES.get(symbol, [company_name, symbol])
    headlines = _fetch_headlines(symbol, aliases)

    if not headlines:
        # Return neutral — don't fail the whole analysis just because of no news
        return _neutral_result(symbol, f"No recent news found for {symbol} — sentiment treated as neutral")

    return _score_with_llm(symbol, aliases[0] if aliases else symbol, headlines)


def _fetch_headlines(symbol: str, aliases: list) -> list:
    """Try each alias against each feed until we have headlines."""
    headlines  = []
    cutoff     = datetime.now() - timedelta(days=30)

    # Try Google News first with the primary company name
    primary = aliases[0] if aliases else symbol
    google_url = RSS_FEEDS[0].format(query=urllib.parse.quote(primary))
    headlines += _parse_feed(google_url, aliases, cutoff, max_items=6)

    if len(headlines) >= 3:
        return headlines

    # Try ET and MoneyControl
    for feed_url in RSS_FEEDS[1:]:
        headlines += _parse_feed(feed_url, aliases, cutoff, max_items=4)
        if len(headlines) >= 5:
            break

    return headlines[:10]


def _parse_feed(feed_url: str, aliases: list, cutoff: datetime, max_items: int) -> list:
    results = []
    try:
        feed  = feedparser.parse(feed_url)
        count = 0
        for entry in feed.entries:
            if count >= max_items:
                break

            title   = entry.get("title", "")
            summary = entry.get("summary", "")
            text    = (title + " " + summary).lower()

            # Check if any alias appears in the article
            matched = any(alias.lower() in text for alias in aliases)
            if not matched:
                continue

            pub_date = None
            if hasattr(entry, "published_parsed") and entry.published_parsed:
                try:
                    pub_date = datetime(*entry.published_parsed[:6])
                    if pub_date < cutoff:
                        continue
                except Exception:
                    pass

            results.append({
                "title":   title,
                "summary": summary[:300],
                "date":    pub_date.strftime("%d-%b-%Y") if pub_date else "recent",
                "source":  feed_url.split("/")[2],
            })
            count += 1

    except Exception as e:
        print(f"  [Sentiment] RSS parse error ({feed_url[:50]}...): {e}")

    return results


def _score_with_llm(symbol: str, company_name: str, headlines: list) -> dict:
    headlines_text = "\n".join(
        f"- [{h['date']}] {h['title']}: {h['summary']}"
        for h in headlines[:8]
    )

    prompt = f"""You are a swing trading assistant analysing news sentiment for {company_name} ({symbol}).

Recent news:
{headlines_text}

Analyse from a swing trader's perspective (5-20 day holding period).
Respond with ONLY a valid JSON object:

{{
  "sentiment_score": <integer 0-100, where 0=very bearish, 50=neutral, 100=very bullish>,
  "direction": "<BULLISH|NEUTRAL|BEARISH>",
  "confidence": "<HIGH|MEDIUM|LOW>",
  "key_positive": ["<up to 2 positive swing trading factors>"],
  "key_negative": ["<up to 2 negative swing trading factors>"],
  "narrative": "<one sentence summary of current market narrative>",
  "swing_impact": "<SHORT_TERM_POSITIVE|SHORT_TERM_NEGATIVE|SHORT_TERM_NEUTRAL>"
}}"""

    raw = call_llm(prompt, max_tokens=400)

    if raw is None:
        return _neutral_result(symbol, "LLM unavailable — sentiment treated as neutral")

    try:
        cleaned = raw.replace("```json", "").replace("```", "").strip()
        start   = cleaned.find("{")
        end     = cleaned.rfind("}") + 1
        parsed  = json.loads(cleaned[start:end])

        return {
            "symbol":          symbol,
            "source":          "google_news_rss + llm",
            "error":           False,
            "headlines_found": len(headlines),
            "sentiment_score": parsed.get("sentiment_score", 50),
            "direction":       parsed.get("direction",       "NEUTRAL"),
            "confidence":      parsed.get("confidence",      "LOW"),
            "key_positive":    parsed.get("key_positive",    []),
            "key_negative":    parsed.get("key_negative",    []),
            "narrative":       parsed.get("narrative",       ""),
            "swing_impact":    parsed.get("swing_impact",    "SHORT_TERM_NEUTRAL"),
            "headlines":       [h["title"] for h in headlines[:5]],
        }

    except (json.JSONDecodeError, ValueError) as e:
        print(f"  [{symbol}] Sentiment JSON parse error: {e}")
        return _neutral_result(symbol, "LLM returned invalid JSON — sentiment neutral")


def _neutral_result(symbol: str, reason: str) -> dict:
    return {
        "symbol":          symbol,
        "source":          "news_rss + llm",
        "error":           False,
        "headlines_found": 0,
        "sentiment_score": 50,
        "direction":       "NEUTRAL",
        "confidence":      "LOW",
        "key_positive":    [],
        "key_negative":    [],
        "narrative":       reason,
        "swing_impact":    "SHORT_TERM_NEUTRAL",
        "headlines":       [],
    }