#!/usr/bin/env python3
import os
import re
import argparse
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
        work: str | None,
        langs: List[str],
        body_only: bool,
) -> List[Tuple[int, str]]:
    """
    Fetch a batch of sentence-level segments that don't yet have an embedding row.
    Returns list of (segment_id, text).
    """
    filters = []
    params: list[object] = []
    if work:
        filters.append("w.title = %s")
        params.append(work)
    if langs:
        filters.append("ed.lang = any(%s)")
        params.append(langs)
    if body_only:
        filters.append("(ed.body_start_segment_id is null or s.id >= ed.body_start_segment_id)")
    scope = "" if not filters else " and " + " and ".join(filters)
    sql = f"""
        select s.id, s.text
        from segment s
        join edition ed on ed.id = s.edition_id
        join work w on w.id = ed.work_id
        left join embedding e on e.segment_id = s.id
        where s.level = 'sentence'
          and e.segment_id is null
          {scope}
        order by s.id
        limit %s
    """
    params.append(limit)
    with conn.cursor() as cur:
        cur.execute(sql, params)
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

def parse_args():
    parser = argparse.ArgumentParser(description="Embed sentence segments that do not yet have vectors.")
    parser.add_argument("--work", help="Restrict embedding to one exact work title")
    parser.add_argument(
        "--lang", action="append", default=[],
        help="Restrict embedding to an edition language; repeat for multiple languages",
    )
    parser.add_argument(
        "--body-only", action="store_true",
        help="Skip segments before each edition's body_start_segment_id",
    )
    return parser.parse_args()


def main():
    args = parse_args()
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
            rows = fetch_unembedded_sentences(conn, BATCH_SIZE, args.work, args.lang, args.body_only)
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
