"""
Concall RAG Store — saves and retrieves earnings call transcripts + analyses.

Architecture:
  - SQLite  : stores full transcripts, metadata, and LLM analysis results
  - ChromaDB: stores embeddings for semantic similarity search
  - Embedding model: sentence-transformers all-MiniLM-L6-v2 (lightweight, ~80MB)

Two main operations:
  save_concall(symbol, transcript, analysis, filing_date, source)
    → called automatically after every successful fetch+analysis
    → idempotent: same symbol+date upserts, does not duplicate

  get_history(symbol, n=4)
    → retrieves the last N analysed quarters for a symbol (newest first)
    → used to build historical context for the LLM prompt

  search_similar(symbol, query, n=3)
    → semantic search: find past concalls where management said similar things
    → useful for "has management mentioned margin pressure before?" queries

Graceful degradation: if ChromaDB or sentence-transformers are not installed,
the module falls back to SQLite-only mode (history works, semantic search disabled).
"""

import os
import json
import sqlite3
import threading
import hashlib
from datetime import datetime
from pathlib import Path

# ── Optional: vector search via ChromaDB ─────────────────────────────────────
try:
    import chromadb
    from chromadb.config import Settings
    _CHROMA_AVAILABLE = True
except ImportError:
    _CHROMA_AVAILABLE = False
    print("  [RAG] ChromaDB not installed — semantic search disabled (pip install chromadb)")

try:
    from sentence_transformers import SentenceTransformer
    _EMBED_AVAILABLE = True
except ImportError:
    _EMBED_AVAILABLE = False
    print("  [RAG] sentence-transformers not installed — semantic search disabled")

# ── Storage paths ─────────────────────────────────────────────────────────────
_BASE_DIR    = Path(os.getenv("RAG_STORE_DIR", "./concall_rag"))
_SQLITE_PATH = _BASE_DIR / "concalls.db"
_CHROMA_DIR  = _BASE_DIR / "chroma"

_BASE_DIR.mkdir(parents=True, exist_ok=True)
_CHROMA_DIR.mkdir(parents=True, exist_ok=True)

_lock = threading.RLock()

# ── SQLite setup ──────────────────────────────────────────────────────────────

def _get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(_SQLITE_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def _init_db():
    with _get_conn() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS concall_records (
                id             TEXT PRIMARY KEY,
                symbol         TEXT NOT NULL,
                company        TEXT,
                filing_date    TEXT,
                source         TEXT,
                quarter_label  TEXT,
                transcript     TEXT,
                tone           TEXT,
                guidance_change TEXT,
                swing_signal   TEXT,
                summary        TEXT,
                key_positives  TEXT,
                key_risks      TEXT,
                saved_at       TEXT NOT NULL
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_symbol ON concall_records(symbol)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_date   ON concall_records(symbol, filing_date)")
        conn.commit()


_init_db()

# ── ChromaDB setup ────────────────────────────────────────────────────────────

_chroma_col  = None
_embed_model = None

def _init_chroma():
    global _chroma_col, _embed_model
    if not _CHROMA_AVAILABLE or not _EMBED_AVAILABLE:
        return
    try:
        client      = chromadb.PersistentClient(
            path=str(_CHROMA_DIR),
            settings=Settings(anonymized_telemetry=False),
        )
        _chroma_col  = client.get_or_create_collection(
            name="concalls",
            metadata={"hnsw:space": "cosine"},
        )
        _embed_model = SentenceTransformer("all-MiniLM-L6-v2")
        print(f"  [RAG] Vector store ready — {_chroma_col.count()} embeddings loaded")
    except Exception as e:
        print(f"  [RAG] ChromaDB init failed: {e} — semantic search disabled")
        _chroma_col  = None
        _embed_model = None


_init_chroma()

# ── Quarter label helper ──────────────────────────────────────────────────────

def _infer_quarter(filing_date: str) -> str:
    """
    Convert a filing date string into a quarter label like 'Q3 FY2025'.
    Indian fiscal year runs April–March, so:
      Apr–Jun → Q1, Jul–Sep → Q2, Oct–Dec → Q3, Jan–Mar → Q4
    Falls back to 'Unknown quarter' if date is unparseable.
    """
    if not filing_date:
        return _current_quarter_label()
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d", "%d %b %Y", "%b %d, %Y"):
        try:
            dt    = datetime.strptime(filing_date[:len(fmt) + 4].strip(), fmt)
            month = dt.month
            year  = dt.year
            if month >= 4:
                fy = year + 1
            else:
                fy = year
            q = {4: 1, 5: 1, 6: 1, 7: 2, 8: 2, 9: 2, 10: 3, 11: 3, 12: 3,
                 1: 4, 2: 4, 3: 4}[month]
            return f"Q{q} FY{fy}"
        except (ValueError, KeyError):
            continue
    return _current_quarter_label()


def _current_quarter_label() -> str:
    now   = datetime.now()
    month = now.month
    year  = now.year
    fy    = year + 1 if month >= 4 else year
    q     = {4: 1, 5: 1, 6: 1, 7: 2, 8: 2, 9: 2, 10: 3, 11: 3, 12: 3,
             1: 4, 2: 4, 3: 4}[month]
    return f"Q{q} FY{fy}"


def _make_id(symbol: str, filing_date: str) -> str:
    """Stable, unique ID for a (symbol, date) pair."""
    raw = f"{symbol}_{filing_date or _current_quarter_label()}"
    return hashlib.md5(raw.encode()).hexdigest()[:16]

# ── Public API ────────────────────────────────────────────────────────────────

def save_concall(
    symbol:      str,
    company:     str,
    transcript:  str,
    analysis:    dict,
    filing_date: str  = None,
    source:      str  = None,
) -> bool:
    """
    Persist a concall transcript + LLM analysis.
    Returns True if saved, False if skipped (neutral/no transcript).

    Called from concall_analyser.py after every successful fetch+analyse.
    """
    # Don't save neutral fallbacks — no real data to store
    if not transcript or analysis.get("source") == "NONE":
        return False

    doc_id        = _make_id(symbol, filing_date)
    quarter_label = _infer_quarter(filing_date)
    saved_at      = datetime.utcnow().isoformat()

    with _lock:
        # ── 1. SQLite ─────────────────────────────────────────────────────────
        try:
            with _get_conn() as conn:
                conn.execute("""
                    INSERT OR REPLACE INTO concall_records
                    (id, symbol, company, filing_date, source, quarter_label,
                     transcript, tone, guidance_change, swing_signal, summary,
                     key_positives, key_risks, saved_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, (
                    doc_id,
                    symbol,
                    company,
                    filing_date,
                    source or analysis.get("source", "UNKNOWN"),
                    quarter_label,
                    transcript[:20000],          # cap at 20K chars
                    analysis.get("tone"),
                    analysis.get("guidance_change"),
                    analysis.get("swing_signal"),
                    analysis.get("summary"),
                    json.dumps(analysis.get("key_positives", [])),
                    json.dumps(analysis.get("key_risks", [])),
                    saved_at,
                ))
                conn.commit()
        except Exception as e:
            print(f"  [RAG] SQLite save failed for {symbol}: {e}")
            return False

        # ── 2. ChromaDB embedding ─────────────────────────────────────────────
        if _chroma_col is not None and _embed_model is not None:
            try:
                # Embed a compact summary: summary + key points (good signal density)
                embed_text = _build_embed_text(symbol, quarter_label, analysis, transcript)
                embedding  = _embed_model.encode(embed_text).tolist()
                _chroma_col.upsert(
                    ids=[doc_id],
                    documents=[embed_text],
                    embeddings=[embedding],
                    metadatas=[{
                        "symbol":          symbol,
                        "quarter":         quarter_label,
                        "tone":            analysis.get("tone", ""),
                        "guidance_change": analysis.get("guidance_change", ""),
                        "swing_signal":    analysis.get("swing_signal", ""),
                        "filing_date":     filing_date or "",
                    }],
                )
            except Exception as e:
                print(f"  [RAG] Chroma upsert failed for {symbol}: {e}")
                # SQLite saved OK — partial success is fine

    print(f"  [RAG] Saved {symbol} {quarter_label} (id={doc_id})")
    return True


def get_history(symbol: str, n: int = 4) -> list[dict]:
    """
    Return the last N concall records for a symbol, newest first.
    Each record is a plain dict with tone, guidance_change, swing_signal,
    summary, key_positives, key_risks, quarter_label, filing_date, source.
    Returns [] if nothing is stored yet.
    """
    try:
        with _get_conn() as conn:
            rows = conn.execute("""
                SELECT quarter_label, filing_date, source, tone,
                       guidance_change, swing_signal, summary,
                       key_positives, key_risks
                FROM concall_records
                WHERE symbol = ?
                ORDER BY saved_at DESC
                LIMIT ?
            """, (symbol, n)).fetchall()
        return [_row_to_dict(r) for r in rows]
    except Exception as e:
        print(f"  [RAG] get_history failed for {symbol}: {e}")
        return []


def search_similar(symbol: str, query: str, n: int = 3) -> list[dict]:
    """
    Semantic search: find past concalls most similar to `query`.
    Restricted to the same symbol (cross-symbol search is future work).
    Returns [] if vector search is unavailable or no results.
    """
    if _chroma_col is None or _embed_model is None:
        return []
    try:
        embedding = _embed_model.encode(query).tolist()
        results   = _chroma_col.query(
            query_embeddings=[embedding],
            n_results=min(n, _chroma_col.count()),
            where={"symbol": symbol},
        )
        metas = results.get("metadatas", [[]])[0]
        docs  = results.get("documents", [[]])[0]
        dists = results.get("distances", [[]])[0]
        out   = []
        for meta, doc, dist in zip(metas, docs, dists):
            out.append({**meta, "similarity": round(1 - dist, 3), "embed_text": doc})
        return out
    except Exception as e:
        print(f"  [RAG] search_similar failed for {symbol}: {e}")
        return []


def store_stats() -> dict:
    """Return basic stats — called by /health endpoint."""
    try:
        with _get_conn() as conn:
            total   = conn.execute("SELECT COUNT(*) FROM concall_records").fetchone()[0]
            symbols = conn.execute("SELECT COUNT(DISTINCT symbol) FROM concall_records").fetchone()[0]
        chroma_count = _chroma_col.count() if _chroma_col else 0
        return {
            "sqlite_records":  total,
            "unique_symbols":  symbols,
            "chroma_embeddings": chroma_count,
            "vector_search":   _chroma_col is not None,
            "store_path":      str(_BASE_DIR.resolve()),
        }
    except Exception as e:
        return {"error": str(e)}

# ── Internal helpers ──────────────────────────────────────────────────────────

def _build_embed_text(symbol: str, quarter: str, analysis: dict, transcript: str) -> str:
    """
    Build a compact text representation for embedding.
    Combines LLM analysis fields + first 500 chars of transcript.
    This gives better semantic signal than embedding the raw transcript alone.
    """
    positives = "; ".join(analysis.get("key_positives", []))
    risks     = "; ".join(analysis.get("key_risks", []))
    return (
        f"{symbol} {quarter}. "
        f"Tone: {analysis.get('tone', '')}. "
        f"Guidance: {analysis.get('guidance_change', '')}. "
        f"Signal: {analysis.get('swing_signal', '')}. "
        f"Summary: {analysis.get('summary', '')} "
        f"Positives: {positives}. "
        f"Risks: {risks}. "
        f"Transcript excerpt: {transcript[:500]}"
    )


def _row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    for field in ("key_positives", "key_risks"):
        try:
            d[field] = json.loads(d[field]) if d[field] else []
        except (json.JSONDecodeError, TypeError):
            d[field] = []
    return d
