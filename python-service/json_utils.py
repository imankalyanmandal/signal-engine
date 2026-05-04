"""
Shared JSON extraction utility for Signal Engine LLM responses.

Gemini 2.5 Flash (and other LLMs) sometimes:
  - Wraps JSON in ```json ... ``` markdown fences
  - Adds preamble text before the JSON
  - Adds explanation text after the JSON
  - Returns unterminated JSON if output is cut off at max_tokens
  - Adds trailing commas after the last key (invalid JSON)
  - Adds // comments inside JSON (invalid JSON)
  - Embeds literal newlines INSIDE string values (Gemini's most subtle bug)
  - Embeds literal tabs / control characters inside string values

This module provides a single robust extractor used by all analysers.
"""

import re
import json


def extract_json(raw: str) -> dict:
    """
    Robustly extract a JSON object from an LLM response string.
    Raises ValueError if no JSON object can be found or parsed.
    """
    if not raw or not raw.strip():
        raise ValueError("Empty LLM response")

    # ── Step 1: strip markdown fences ────────────────────────────────────────
    cleaned = re.sub(r'```(?:json)?\s*', '', raw).strip()
    cleaned = cleaned.replace('```', '').strip()

    # ── Step 1.5: pre-escape control chars inside strings ───────────────────
    # Must happen before boundary detection — otherwise an unescaped newline
    # inside a string value confuses the brace-matching scanner and we cut the
    # JSON at the wrong place.
    cleaned = _escape_control_chars_in_strings(cleaned)

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
    Note: control-character escaping happens earlier in extract_json's Step 1.5
    so this function only handles syntactic issues.
    """
    # Remove single-line // comments
    fragment = re.sub(r'//[^\n]*', '', fragment)

    # Remove trailing commas before } or ]
    fragment = re.sub(r',\s*([}\]])', r'\1', fragment)

    stripped = fragment.rstrip()

    # Only do truncation-recovery if the JSON does NOT already end cleanly.
    # Otherwise the regex below over-eagerly chops the last key from valid input.
    if not stripped.endswith('}') and not stripped.endswith(']'):
        # Step A: count quotes — odd count means an unterminated string at the end
        if fragment.count('"') % 2 == 1:
            fragment = fragment + '"'  # close the string
        # Step B: strip incomplete last key/value (typically `, "key": "..."`)
        fragment = re.sub(r',?\s*"[^"]*"\s*:\s*"[^"]*"\s*$', '', fragment)
        fragment = re.sub(r',?\s*"[^"]*"\s*:\s*[^,}\]]*$',   '', fragment)
        # Step C: clean up any leftover trailing commas/whitespace
        fragment = fragment.rstrip().rstrip(',').rstrip()
        # Step D: close the object
        if not fragment.endswith('}'):
            fragment = fragment + '}'

    return fragment


def _escape_control_chars_in_strings(fragment: str) -> str:
    """
    Walk the fragment character by character. While inside a quoted string,
    replace literal control characters (newline, tab, carriage return,
    backspace, form feed) with their JSON-escaped equivalents.

    This fixes the most common Gemini bug — embedding line breaks in summary
    fields without escaping them as \\n. Outside strings, control chars are
    left alone (they're allowed as JSON whitespace).
    """
    out       = []
    in_string = False
    escape    = False

    for ch in fragment:
        if escape:
            out.append(ch)
            escape = False
            continue

        if ch == '\\' and in_string:
            out.append(ch)
            escape = True
            continue

        if ch == '"':
            out.append(ch)
            in_string = not in_string
            continue

        if in_string:
            if   ch == '\n': out.append('\\n')
            elif ch == '\r': out.append('\\r')
            elif ch == '\t': out.append('\\t')
            elif ch == '\b': out.append('\\b')
            elif ch == '\f': out.append('\\f')
            elif ord(ch) < 0x20:
                out.append('\\u%04x' % ord(ch))
            else:
                out.append(ch)
        else:
            out.append(ch)

    return ''.join(out)


def safe_json_loads(raw: str, fallback: dict) -> dict:
    """
    Like extract_json but never raises — returns fallback dict on any failure.
    """
    try:
        return extract_json(raw)
    except Exception as e:
        print(f"  [JSON] Parse failed: {e}")
        return fallback
