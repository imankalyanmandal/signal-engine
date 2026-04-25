"""
LLM Client — 5-tier cloud-only fallback chain. No local dependencies.

Priority order:
  Tier 1: Gemini 2.5 Flash      (Google AI Studio  — 1,500 req/day, best quality)
  Tier 2: Groq llama-3.3-70b    (console.groq.com  — fastest, 30 RPM free)
  Tier 3: Cerebras gpt-oss-120b (cloud.cerebras.ai — 1M tokens/day free)
  Tier 4: OpenRouter :free       (openrouter.ai     — 30 free models, 200 req/day)
  Tier 5: GitHub Models gpt-4o  (github.com/settings/tokens — free for all GitHub users)

All tiers except Gemini use the OpenAI-compatible Chat Completions format.
Providers with no API key are silently skipped — need at least ONE key.

How to get keys (all free, no credit card):
  Gemini:     https://aistudio.google.com  → Get API key
  Groq:       https://console.groq.com     → API Keys → Create
  Cerebras:   https://cloud.cerebras.ai    → Sign up → API Keys
  OpenRouter: https://openrouter.ai/keys   → Create Key
  GitHub:     https://github.com/settings/tokens → New token (read:user scope only)
"""

import os
import time
import requests
from dotenv import load_dotenv

load_dotenv()

# ── API Keys ──────────────────────────────────────────────────────────────────

GEMINI_API_KEY     = os.getenv("GEMINI_API_KEY", "")
GROQ_API_KEY       = os.getenv("GROQ_API_KEY", "")
CEREBRAS_API_KEY   = os.getenv("CEREBRAS_API_KEY", "")
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
GITHUB_TOKEN       = os.getenv("GITHUB_TOKEN", "")

GEMINI_RETRY_DELAY = float(os.getenv("GEMINI_RETRY_DELAY", "4"))

# ── Models ────────────────────────────────────────────────────────────────────

GEMINI_MODEL     = "gemini-2.5-flash"        # upgraded from 2.0-flash (deprecated June 2026)
GROQ_MODEL       = "llama-3.3-70b-versatile"
CEREBRAS_MODEL   = "gpt-oss-120b"            # OpenAI open-weight 120B on Cerebras hardware
OPENROUTER_MODEL = "openrouter/auto"         # auto-routes to best available free model
GITHUB_MODEL     = "gpt-4o"                  # GPT-4o free via GitHub Marketplace

# ── Endpoints ─────────────────────────────────────────────────────────────────

GEMINI_URL     = (
    f"https://generativelanguage.googleapis.com/v1beta/models/"
    f"{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}"
)
GROQ_URL       = "https://api.groq.com/openai/v1/chat/completions"
CEREBRAS_URL   = "https://api.cerebras.ai/v1/chat/completions"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
GITHUB_URL     = "https://models.inference.ai.azure.com/chat/completions"


# ── Public entry point ────────────────────────────────────────────────────────

def call_llm(prompt: str, max_tokens: int = 1000) -> str:
    """
    Call LLM with 5-tier automatic cloud fallback.

    Returns the response text.
    Raises RuntimeError only if ALL configured providers fail simultaneously.
    """
    providers = [
        ("Gemini 2.5 Flash", _call_gemini,     GEMINI_API_KEY),
        ("Groq",             _call_groq,        GROQ_API_KEY),
        ("Cerebras",         _call_cerebras,    CEREBRAS_API_KEY),
        ("OpenRouter",       _call_openrouter,  OPENROUTER_API_KEY),
        ("GitHub Models",    _call_github,      GITHUB_TOKEN),
    ]

    last_error = None

    for name, fn, key in providers:
        if not key:
            print(f"  [LLM] {name} skipped — no key in .env")
            continue
        try:
            result = fn(prompt, max_tokens)
            if name == "Gemini 2.5 Flash":
                time.sleep(GEMINI_RETRY_DELAY)   # rate limit buffer
            print(f"  [LLM] ✓ {name}")
            return result
        except Exception as e:
            last_error = e
            print(f"  [LLM] {name} failed: {e} — trying next")

    raise RuntimeError(
        f"All configured LLM providers failed. Last error: {last_error}. "
        f"Check your API keys in .env"
    )


# ── Provider implementations ──────────────────────────────────────────────────

def _call_gemini(prompt: str, max_tokens: int) -> str:
    """Gemini 2.5 Flash — Google AI Studio REST API (not OpenAI-compatible format)."""
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "maxOutputTokens": max_tokens,
            "temperature":     0.3,
        },
    }
    resp = requests.post(GEMINI_URL, json=payload, timeout=30)
    resp.raise_for_status()

    data       = resp.json()
    candidates = data.get("candidates", [])
    if not candidates:
        raise RuntimeError(f"Gemini returned no candidates: {data}")

    parts = candidates[0].get("content", {}).get("parts", [])
    if not parts:
        raise RuntimeError(f"Gemini returned empty parts: {data}")

    return parts[0]["text"].strip()


def _call_openai_compatible(
    url:           str,
    api_key:       str,
    model:         str,
    prompt:        str,
    max_tokens:    int,
    extra_headers: dict = None,
) -> str:
    """
    Generic OpenAI-compatible Chat Completions call.
    Groq, Cerebras, OpenRouter, and GitHub Models all use this same format.
    """
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type":  "application/json",
    }
    if extra_headers:
        headers.update(extra_headers)

    payload = {
        "model":       model,
        "messages":    [{"role": "user", "content": prompt}],
        "max_tokens":  max_tokens,
        "temperature": 0.3,
    }

    resp = requests.post(url, json=payload, headers=headers, timeout=30)
    resp.raise_for_status()

    data    = resp.json()
    choices = data.get("choices", [])
    if not choices:
        raise RuntimeError(f"No choices in response: {data}")

    content = choices[0].get("message", {}).get("content", "").strip()
    if not content:
        raise RuntimeError(f"Empty content in response: {data}")

    return content


def _call_groq(prompt: str, max_tokens: int) -> str:
    """Groq — fastest inference, llama-3.3-70b, 30 RPM free."""
    return _call_openai_compatible(
        GROQ_URL, GROQ_API_KEY, GROQ_MODEL, prompt, max_tokens
    )


def _call_cerebras(prompt: str, max_tokens: int) -> str:
    """
    Cerebras — 1M tokens/day free, ultra-fast wafer-scale chip inference.
    Free tier has 8K context limit — long prompts are truncated automatically.
    """
    if len(prompt) > 24000:   # ~8K tokens at ~3 chars/token
        prompt = prompt[:24000] + "\n\n[Prompt truncated for 8K context limit]"

    return _call_openai_compatible(
        CEREBRAS_URL, CEREBRAS_API_KEY, CEREBRAS_MODEL, prompt, max_tokens
    )


def _call_openrouter(prompt: str, max_tokens: int) -> str:
    """
    OpenRouter — auto-routes to best available free model.
    30 free models available, 200 req/day without credits.
    """
    return _call_openai_compatible(
        OPENROUTER_URL, OPENROUTER_API_KEY, OPENROUTER_MODEL, prompt, max_tokens,
        extra_headers={
            "HTTP-Referer": "https://github.com/signal-engine",
            "X-Title":      "Signal Engine",
        }
    )


def _call_github(prompt: str, max_tokens: int) -> str:
    """
    GitHub Models — GPT-4o free for all GitHub users.
    Endpoint: https://models.inference.ai.azure.com
    Auth:     GitHub personal access token (Settings → Developer settings → Tokens)
    Limits:   8K input / 4K output per request, daily rate limits apply.
    """
    return _call_openai_compatible(
        GITHUB_URL, GITHUB_TOKEN, GITHUB_MODEL, prompt, max_tokens
    )


# ── Health check ──────────────────────────────────────────────────────────────

def check_providers() -> dict:
    """
    Returns which providers are configured.
    Called by GET /health in market_service.py.
    """
    configured = {
        "gemini_2_5_flash": bool(GEMINI_API_KEY),
        "groq":             bool(GROQ_API_KEY),
        "cerebras":         bool(CEREBRAS_API_KEY),
        "openrouter":       bool(OPENROUTER_API_KEY),
        "github_models":    bool(GITHUB_TOKEN),
    }
    configured["active_count"] = sum(configured.values())
    configured["fallback_chain"] = [
        k for k, v in configured.items()
        if v and k != "active_count"
    ]
    return configured