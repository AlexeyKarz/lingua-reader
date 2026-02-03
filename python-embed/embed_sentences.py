#!/usr/bin/env python3
import os
import re
from datetime import datetime, timezone
from typing import List, Tuple

import numpy as np
import psycopg
from dotenv import load_dotenv
from pgvector.psycopg import register_vector
from sentence_transformers import SentenceTransformer

# -------------------------
# Config
# -------------------------

MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
BATCH_SIZE = 256  # number of sentences per DB batch

# -------------------------
# Logging helper (logfmt for Loki)
# -------------------------

def log(event: str, level: str = "INFO", **fields) -> None:
    ts = datetime.now(timezone.utc).isoformat()
    parts = [f"ts={ts}", f"level={level}", f"event={event}"]
    for k, v in fields.items():
        if v is None:
            continue
        if isinstance(v, bool):
            val = "true" if v else "false"
        else:
            val = str(v)
        if re.search(r"[\\s=]", val):
            val = "\"" + val.replace("\"", "\\\"") + "\""
        parts.append(f"{k}={val}")
    print(" ".join(parts))

# -------------------------
# DB helpers
# -------------------------

def get_conn() -> psycopg.Connection:
    # Load DATABASE_URL from .env or environment
    load_dotenv()
    db_url = os.getenv("DATABASE_URL")
    if not db_url:
        raise RuntimeError("DATABASE_URL is not set (in env or .env)")
    conn = psycopg.connect(db_url)
    # Register pgvector adapter
    register_vector(conn)
    return conn


def fetch_unembedded_sentences(
        conn: psycopg.Connection,
        limit: int,
) -> List[Tuple[int, str]]:
    """
    Fetch a batch of sentence-level segments that don't yet have an embedding row.
    Returns list of (segment_id, text).
    """
    sql = """
        select s.id, s.text
        from segment s
        left join embedding e on e.segment_id = s.id
        where s.level = 'sentence'
          and e.segment_id is null
        order by s.id
        limit %s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (limit,))
        rows = cur.fetchall()
    return [(r[0], r[1]) for r in rows]


def upsert_embeddings(
        conn: psycopg.Connection,
        segment_ids: List[int],
        vectors: np.ndarray,
        model_name: str,
) -> None:
    """
    Insert or update embedding rows.

    vectors: shape (N, 384)
    """
    sql = """
        insert into embedding (segment_id, model, vec)
        values (%s, %s, %s)
        on conflict (segment_id) do update
            set model = excluded.model,
                vec   = excluded.vec
    """
    with conn.cursor() as cur:
        for sid, vec in zip(segment_ids, vectors):
            # vec is a 1D numpy array, pgvector will adapt it
            cur.execute(sql, (sid, model_name, vec))
    conn.commit()


# -------------------------
# Main embedding loop
# -------------------------

def main():
    log("embed_model_load", model=MODEL_NAME)
    model = SentenceTransformer(MODEL_NAME)

    conn = get_conn()
    try:
        # Just in case extension wasn't enabled somewhere
        with conn.cursor() as cur:
            cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
        conn.commit()

        total_processed = 0

        while True:
            rows = fetch_unembedded_sentences(conn, BATCH_SIZE)
            if not rows:
                log("embed_done", total_processed=total_processed)
                break

            segment_ids, texts = zip(*rows)
            log("embed_batch", batch_size=len(texts), start_segment_id=segment_ids[0], model=MODEL_NAME)

            # sentence-transformers returns numpy array (n_sentences, 384)
            vectors = model.encode(
                list(texts),
                batch_size=64,
                show_progress_bar=True,
                convert_to_numpy=True,
                normalize_embeddings=True  # good for cosine similarity
            )

            upsert_embeddings(conn, list(segment_ids), vectors, MODEL_NAME)
            total_processed += len(texts)
            log("embed_progress", total_processed=total_processed)

    finally:
        conn.close()
        log("embed_connection_closed")


if __name__ == "__main__":
    main()
