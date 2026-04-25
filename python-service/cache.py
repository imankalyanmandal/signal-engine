"""
Cache layer for Signal Engine Python microservice.

Two cache tiers:
  1. Symbol list cache   — refreshes monthly (index constituents rarely change)
  2. Candle data cache   — refreshes every 4 hours (intraday staleness acceptable for swing trading)

All cache entries include a timestamp and TTL.
Thread-safe using a single RLock.
"""

import time
import threading
from typing import Optional, Any

# ── TTL constants ──────────────────────────────────────────────────────────────
SYMBOL_LIST_TTL  = 30 * 24 * 60 * 60   # 30 days in seconds
CANDLE_DATA_TTL  =  4 * 60 * 60        # 4 hours in seconds
REGIME_TTL       =  1 * 60 * 60        # 1 hour (market regime changes slowly)
LAYER2_TTL       =  6 * 60 * 60        # 6 hours (fundamentals/sentiment)


class SignalEngineCache:
    """
    Thread-safe in-memory cache with TTL support.

    Keys follow a namespaced format:
      symbols:{index}                  → list of NSE symbols
      candles:{symbol}:{period}        → list of candle dicts
      regime                           → market regime result
      layer2:{symbol}                  → Layer 2 analysis result
    """

    def __init__(self):
        self._store: dict[str, dict] = {}
        self._lock  = threading.RLock()
        self._hits  = 0
        self._misses = 0

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            entry = self._store.get(key)
            if entry is None:
                self._misses += 1
                return None
            if time.time() > entry["expires_at"]:
                del self._store[key]
                self._misses += 1
                return None
            self._hits += 1
            return entry["value"]

    def set(self, key: str, value: Any, ttl: int) -> None:
        with self._lock:
            self._store[key] = {
                "value":      value,
                "cached_at":  time.time(),
                "expires_at": time.time() + ttl,
            }

    def delete(self, key: str) -> bool:
        with self._lock:
            if key in self._store:
                del self._store[key]
                return True
            return False

    def clear_prefix(self, prefix: str) -> int:
        """Delete all keys starting with prefix. Returns count deleted."""
        with self._lock:
            to_delete = [k for k in self._store if k.startswith(prefix)]
            for k in to_delete:
                del self._store[k]
            return len(to_delete)

    def clear_all(self) -> int:
        with self._lock:
            count = len(self._store)
            self._store.clear()
            return count

    def stats(self) -> dict:
        with self._lock:
            now = time.time()
            valid   = sum(1 for e in self._store.values() if now <= e["expires_at"])
            expired = len(self._store) - valid
            return {
                "total_entries": len(self._store),
                "valid_entries": valid,
                "expired_entries": expired,
                "hits":   self._hits,
                "misses": self._misses,
                "hit_rate": f"{self._hits/(self._hits+self._misses)*100:.1f}%" if (self._hits+self._misses) > 0 else "0%",
            }

    def keys_info(self) -> list:
        """List all keys with their TTL remaining (for /cache/status endpoint)."""
        with self._lock:
            now = time.time()
            result = []
            for key, entry in self._store.items():
                ttl_remaining = entry["expires_at"] - now
                if ttl_remaining > 0:
                    result.append({
                        "key":           key,
                        "ttl_remaining": f"{int(ttl_remaining)}s",
                        "cached_at":     time.strftime("%Y-%m-%d %H:%M:%S",
                                                       time.localtime(entry["cached_at"])),
                    })
            return sorted(result, key=lambda x: x["key"])


# ── Global singleton ───────────────────────────────────────────────────────────
cache = SignalEngineCache()


# ── Convenience helpers ────────────────────────────────────────────────────────

def get_symbols(index: str) -> Optional[list]:
    return cache.get(f"symbols:{index}")

def set_symbols(index: str, symbols: list) -> None:
    cache.set(f"symbols:{index}", symbols, SYMBOL_LIST_TTL)
    print(f"  [Cache] Stored {len(symbols)} symbols for {index} (TTL: 30 days)")

def get_candles(symbol: str, period: str) -> Optional[list]:
    return cache.get(f"candles:{symbol}:{period}")

def set_candles(symbol: str, period: str, candles: list) -> None:
    cache.set(f"candles:{symbol}:{period}", candles, CANDLE_DATA_TTL)

def get_layer2(symbol: str) -> Optional[dict]:
    return cache.get(f"layer2:{symbol}")

def set_layer2(symbol: str, result: dict) -> None:
    cache.set(f"layer2:{symbol}", result, LAYER2_TTL)

def invalidate_symbols(index: str = None) -> int:
    """Force refresh of symbol lists. Pass index to clear one, None to clear all."""
    if index:
        deleted = 1 if cache.delete(f"symbols:{index}") else 0
    else:
        deleted = cache.clear_prefix("symbols:")
    print(f"  [Cache] Invalidated {deleted} symbol cache entries")
    return deleted

def invalidate_candles(symbol: str = None) -> int:
    """Force refresh of candle data. Pass symbol to clear one, None to clear all."""
    if symbol:
        deleted = cache.clear_prefix(f"candles:{symbol}:")
    else:
        deleted = cache.clear_prefix("candles:")
    print(f"  [Cache] Invalidated {deleted} candle cache entries")
    return deleted
