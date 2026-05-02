"""
Shared JSON extraction utility for Signal Engine LLM responses.

Gemini 2.5 Flash (and other LLMs) sometimes:
  - Wraps JSON in ```json ... ``` markdown fences
  - Adds preamble text before the JSON
  - Adds explanation text after the JSON
  - Returns unterminated JSON if output is cut off at max_tokens
  - Adds trailing commas after the last key (invalid JSON)
  - Adds // comments inside JSON (invalid JSON)
  - Uses single quotes instead of double quotes

This module provides a single robust extractor used by all analysers.
"""

import re
import json


def extract_json(raw: str) -> dict:
    """
    Robustly extract a JSON object from an LLM response string.

    Handles:
      - Markdown code fences (```json ... ```)
      - Preamble text before the JSON object
      - Trailing text after the JSON object
      - Unterminated strings / cut-off responses
      - Trailing commas after last key (Gemini's most common bad habit)
      - Single-line // comments inside JSON
      - Single quotes instead of double quotes

    Raises ValueError if no JSON object can be found or parsed.
    """
    if not raw or not raw.strip():
        raise ValueError("Empty LLM response")

    # ── Step 1: strip markdown fences ────────────────────────────────────────
    cleaned = re.sub(r'```(?:json)?\s*', '', raw).strip()
    cleaned = cleaned.replace('```', '').strip()

    # ── Step 2: find the outermost JSON object boundaries ────────────────────
    start = cleaned.find('{')
    if start == -1:
        raise ValueError(f"No JSON object found in response: {cleaned[:100]}")

    depth     = 0
    end       = -1
    in_string = False
    escape    = False

    for i, ch in enumerate(cleaned[start:], start):
        if escape:
            escape = False
            continue
        if ch == '\\' and in_string:
            escape = True
            continue
        if ch == '"' and not escape:
            in_string = not in_string
            continue
        if in_string:
            continue
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break

    fragment = cleaned[start:end] if end != -1 else cleaned[start:]

    # ── Step 3: try parsing as-is first ──────────────────────────────────────
    try:
        return json.loads(fragment)
    except json.JSONDecodeError:
        pass

    # ── Step 4: apply repairs and retry ──────────────────────────────────────
    fragment = _repair_json(fragment)
    try:
        return json.loads(fragment)
    except json.JSONDecodeError as e:
        raise ValueError(
            f"Could not parse JSON even after recovery. "
            f"Error: {e}. Raw response start: {raw[:200]}"
        )


def _repair_json(fragment: str) -> str:
    """
    Apply a series of repairs to malformed JSON from LLMs.
    Each repair is independent and non-destructive.
    """
    # Remove single-line // comments (not valid JSON)
    fragment = re.sub(r'//[^\n]*', '', fragment)

    # Remove trailing commas before } or ] (Gemini's most common mistake)
    fragment = re.sub(r',\s*([}\]])', r'\1', fragment)

    # If the JSON was cut off mid-string, strip the incomplete last key
    fragment = re.sub(r',?\s*"[^"]*$', '', fragment)

    # Strip any trailing comma left after the above
    fragment = fragment.rstrip().rstrip(',')

    # Close the object if it was cut off before the final }
    if not fragment.rstrip().endswith('}'):
        fragment = fragment.rstrip() + '}'

    return fragment


def safe_json_loads(raw: str, fallback: dict) -> dict:
    """
    Like extract_json but never raises — returns fallback dict on any failure.
    Use this when you want silent degradation.
    """
    try:
        return extract_json(raw)
    except Exception as e:
        print(f"  [JSON] Parse failed: {e}")
        return fallback