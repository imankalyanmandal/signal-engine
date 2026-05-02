"""
LLM provider configuration for Signal Engine.

5 cloud providers in fallback order — no local dependencies, no Ollama.
All keys optional individually, need at least ONE configured.
"""

import os
from dotenv import load_dotenv

load_dotenv(override=True)  # Load .env — safe to call multiple times, dotenv is idempotent

GEMINI_API_KEY     = os.getenv("GEMINI_API_KEY", "")
GROQ_API_KEY       = os.getenv("GROQ_API_KEY", "")
CEREBRAS_API_KEY   = os.getenv("CEREBRAS_API_KEY", "")
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
GITHUB_TOKEN       = os.getenv("GITHUB_TOKEN", "")
GEMINI_RETRY_DELAY = float(os.getenv("GEMINI_RETRY_DELAY", "4"))

_MOCK_MODE = os.getenv("MOCK_MODE", "false").lower() == "true"

def is_mock() -> bool:
    return _MOCK_MODE