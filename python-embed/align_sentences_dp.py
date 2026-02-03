#!/usr/bin/env python3
"""
DP-based sentence alignment between two editions of the same work,
using precomputed sentence embeddings from pgvector.

This is closer in spirit to Gale–Church / Vecalign-style alignment:
- monotonic
- allows 1-1, 1-0, 0-1, 2-1, 1-2
- scores matches using cosine similarity of multilingual embeddings

Usage example:

    source .venv/bin/activate
    export DATABASE_URL="postgresql://mlr:mlr_password@localhost:5432/mlr"

    python align_sentences_dp.py \
        --work "Faust (Part I)" \
        --src de \
        --tgt en \
        --method dp-mlMiniLM-v1 \
        --min-score 0.2 \
        --gap-penalty 0.4 \
        --merge-penalty 0.2
"""

import os
import re
import argparse
from datetime import datetime, timezone
from typing import List, Tuple, Optional

import numpy as np
import psycopg
from dotenv import load_dotenv
from pgvector.psycopg import register_vector


# -------------------------
# DB connection helper
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

def get_conn() -> psycopg.Connection:
    load_dotenv()
    db_url = os.getenv("DATABASE_URL")
    if not db_url:
        raise RuntimeError("DATABASE_URL is not set (env or .env)")
    conn = psycopg.connect(db_url)
    register_vector(conn)
    return conn


# -------------------------
# Fetch edition & sentence data
# -------------------------

def fetch_edition_ids(conn, work_title: str, src_lang: str, tgt_lang: str) -> Tuple[int, int, int]:
    """
    Returns (work_id, src_edition_id, tgt_edition_id) based on work title and langs.
    Assumes one work row with that title and at least one edition per language.
    """
    sql = """
        select w.id as work_id,
               max(case when e.lang = %s then e.id end) as src_id,
               max(case when e.lang = %s then e.id end) as tgt_id
        from work w
        join edition e on e.work_id = w.id
        where w.title = %s
        group by w.id
        limit 1
    """
    with conn.cursor() as cur:
        cur.execute(sql, (src_lang, tgt_lang, work_title))
        row = cur.fetchone()

    if not row or row[1] is None or row[2] is None:
        raise RuntimeError(
            f"Could not find editions for work='{work_title}' src='{src_lang}' tgt='{tgt_lang}'"
        )

    work_id, src_id, tgt_id = row
    return int(work_id), int(src_id), int(tgt_id)

def fetch_edition_ids_and_body_start(conn, work_title, src_lang, tgt_lang):
    sql = """
        select w.id as work_id,
               max(case when e.lang = %s then e.id end) as src_id,
               max(case when e.lang = %s then e.id end) as tgt_id,
               max(case when e.lang = %s then e.body_start_segment_id end) as src_body,
               max(case when e.lang = %s then e.body_start_segment_id end) as tgt_body
        from work w
        join edition e on e.work_id = w.id
        where w.title = %s
        group by w.id
        limit 1
    """
    with conn.cursor() as cur:
        cur.execute(sql, (src_lang, tgt_lang, src_lang, tgt_lang, work_title))
        row = cur.fetchone()
    if not row:
        raise RuntimeError("...")

    work_id, src_id, tgt_id, src_body, tgt_body = row
    return int(work_id), int(src_id), int(tgt_id), src_body, tgt_body



def fetch_sentences_with_vecs(conn, edition_id: int, body_start_id: Optional[int]):
    """
    Returns (segment_ids, vectors) for all sentence-level segments in order
    for a given edition. Requires embedding rows to exist for those segments.
    """
    if body_start_id is None:
        where_extra = ""
        params = (edition_id,)
    else:
        where_extra = "and s.id >= %s"
        params = (edition_id, body_start_id)

    sql = f"""
        select s.id, e.vec
        from segment s
        join embedding e on e.segment_id = s.id
        where s.edition_id = %s
          and s.level = 'sentence'
          {where_extra}
        order by s.id
    """
    with conn.cursor() as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()

    if not rows:
        raise RuntimeError(f"No sentence+embedding rows found for edition_id={edition_id}")

    ids = [int(r[0]) for r in rows]
    vecs = np.stack([np.array(r[1], dtype=float) for r in rows])

    # L2-normalize for cosine similarity
    norms = np.linalg.norm(vecs, axis=1, keepdims=True) + 1e-9
    vecs = vecs / norms
    return ids, vecs


# -------------------------
# DP-based alignment
# -------------------------

def dp_align(
        src_ids: List[int],
        src_vecs: np.ndarray,
        tgt_ids: List[int],
        tgt_vecs: np.ndarray,
        gap_penalty: float = 0.4,
        merge_penalty: float = 0.2,
) -> List[Tuple[int, int, float]]:
    """
    Dynamic programming aligner:
    - src_* = source language (e.g. DE)
    - tgt_* = target language (e.g. EN)

    Operations:
      1 = 1-1  (src[i-1] ↔ tgt[j-1])
      2 = 1-0  (delete src[i-1])
      3 = 0-1  (insert tgt[j-1])
      4 = 2-1  (src[i-2], src[i-1] ↔ tgt[j-1])   # we emit two pairs
      5 = 1-2  (src[i-1] ↔ tgt[j-2], tgt[j-1])   # we emit two pairs

    Returns list of (src_segment_id, tgt_segment_id, score).
    """

    N = len(src_ids)
    M = len(tgt_ids)

    # Similarity matrix S[i,j] for i in [0..N-1], j in [0..M-1]
    # dot product of normalized vectors = cosine similarity in [-1, 1]
    log("dp_similarity_matrix", src_count=N, tgt_count=M)
    S = np.dot(src_vecs, tgt_vecs.T).astype(np.float32)

    # DP and backpointer tables; (N+1)x(M+1)
    dp = np.zeros((N + 1, M + 1), dtype=np.float32)
    back = np.zeros((N + 1, M + 1), dtype=np.int8)

    # Initialization: allow skipping prefixes with a small penalty
    for i in range(1, N + 1):
        dp[i, 0] = dp[i - 1, 0] - gap_penalty
        back[i, 0] = 2  # 1-0
    for j in range(1, M + 1):
        dp[0, j] = dp[0, j - 1] - gap_penalty
        back[0, j] = 3  # 0-1

    # Fill DP
    log("dp_start", src_count=N, tgt_count=M)
    for i in range(1, N + 1):
        if i % 500 == 0:
            log("dp_progress", row=i, total=N)
        for j in range(1, M + 1):
            best = dp[i - 1, j - 1] + S[i - 1, j - 1]  # 1-1
            op = 1

            # 1-0 (delete src)
            cand = dp[i - 1, j] - gap_penalty
            if cand > best:
                best = cand
                op = 2

            # 0-1 (insert tgt)
            cand = dp[i, j - 1] - gap_penalty
            if cand > best:
                best = cand
                op = 3

            # 2-1 merge
            if i >= 2:
                avg = 0.5 * (S[i - 2, j - 1] + S[i - 1, j - 1])
                cand = dp[i - 2, j - 1] + avg - merge_penalty
                if cand > best:
                    best = cand
                    op = 4

            # 1-2 merge
            if j >= 2:
                avg = 0.5 * (S[i - 1, j - 2] + S[i - 1, j - 1])
                cand = dp[i - 1, j - 2] + avg - merge_penalty
                if cand > best:
                    best = cand
                    op = 5

            dp[i, j] = best
            back[i, j] = op

    # Backtrack
    log("dp_backtrack_start")
    pairs: List[Tuple[int, int, float]] = []
    i, j = N, M

    while i > 0 or j > 0:
        op = back[i, j]
        if op == 1:
            # 1-1
            score = float(S[i - 1, j - 1])
            pairs.append((src_ids[i - 1], tgt_ids[j - 1], score))
            i -= 1
            j -= 1
        elif op == 2:
            # 1-0
            i -= 1
        elif op == 3:
            # 0-1
            j -= 1
        elif op == 4 and i >= 2 and j >= 1:
            # 2-1: align both src[i-2] and src[i-1] to tgt[j-1]
            avg = float(0.5 * (S[i - 2, j - 1] + S[i - 1, j - 1]))
            pairs.append((src_ids[i - 2], tgt_ids[j - 1], avg))
            pairs.append((src_ids[i - 1], tgt_ids[j - 1], avg))
            i -= 2
            j -= 1
        elif op == 5 and i >= 1 and j >= 2:
            # 1-2: align src[i-1] to both tgt[j-2] and tgt[j-1]
            avg = float(0.5 * (S[i - 1, j - 2] + S[i - 1, j - 1]))
            pairs.append((src_ids[i - 1], tgt_ids[j - 2], avg))
            pairs.append((src_ids[i - 1], tgt_ids[j - 1], avg))
            i -= 1
            j -= 2
        else:
            # Fallback (should mainly happen at the edges)
            if i > 0:
                i -= 1
            elif j > 0:
                j -= 1
            else:
                break

    pairs.reverse()
    log("dp_pairs_raw", count=len(pairs))
    return pairs


# -------------------------
# Write alignments
# -------------------------

def write_alignments(
        conn,
        work_id: int,
        src_edition_id: int,
        tgt_edition_id: int,
        pairs: List[Tuple[int, int, float]],
        method_name: str,
        min_score: float,
) -> int:
    """
    Insert / upsert alignments into the existing alignment table:

        id, left_segment_id, right_segment_id, method, score, created_at,
        work_id, edition_src_id, edition_tgt_id

    Respects UNIQUE (left_segment_id, right_segment_id, method).
    """
    sql = """
        insert into alignment (
            left_segment_id,
            right_segment_id,
            method,
            score,
            created_at,
            work_id,
            edition_src_id,
            edition_tgt_id
        )
        values (%s, %s, %s, %s, now(), %s, %s, %s)
        on conflict (left_segment_id, right_segment_id, method)
        do update set
            score          = excluded.score,
            created_at     = excluded.created_at,
            work_id        = excluded.work_id,
            edition_src_id = excluded.edition_src_id,
            edition_tgt_id = excluded.edition_tgt_id
    """

    kept = 0
    with conn.cursor() as cur:
        for src_seg_id, tgt_seg_id, score in pairs:
            if score < min_score:
                continue
            cur.execute(
                sql,
                (
                    src_seg_id,
                    tgt_seg_id,
                    method_name,
                    float(score),
                    work_id,
                    src_edition_id,
                    tgt_edition_id,
                ),
            )
            kept += 1
    conn.commit()
    log("align_write_done", kept=kept, min_score=min_score)
    return kept


# -------------------------
# CLI
# -------------------------

def parse_args():
    p = argparse.ArgumentParser(description="DP-based sentence alignment between two editions.")
    p.add_argument("--work", required=True, help="Work title, e.g. 'Faust (Part I)'")
    p.add_argument("--src", required=True, help="Source language code, e.g. 'de'")
    p.add_argument("--tgt", required=True, help="Target language code, e.g. 'en'")
    p.add_argument(
        "--method",
        default="dp-mlMiniLM-v1",
        help="Method tag stored in alignment.method (default: dp-mlMiniLM-v1)",
    )
    p.add_argument(
        "--min-score",
        type=float,
        default=0.35,
        help="Minimum cosine similarity to keep an alignment when writing to DB (default: 0.35)",
    )
    p.add_argument(
        "--gap-penalty",
        type=float,
        default=0.4,
        help="Penalty for 1-0 / 0-1 operations (default: 0.4)",
    )
    p.add_argument(
        "--merge-penalty",
        type=float,
        default=0.2,
        help="Penalty for 2-1 / 1-2 merges (default: 0.2)",
    )
    return p.parse_args()


def main():
    args = parse_args()

    conn = get_conn()
    try:
        work_id, src_ed, tgt_ed, src_body, tgt_body = fetch_edition_ids_and_body_start(conn, args.work, args.src, args.tgt)
        log(
            "align_start",
            work=args.work,
            work_id=work_id,
            src=args.src,
            tgt=args.tgt,
            src_edition_id=src_ed,
            tgt_edition_id=tgt_ed,
            src_body_start=src_body,
            tgt_body_start=tgt_body
        )

        src_ids, src_vecs = fetch_sentences_with_vecs(conn, src_ed, src_body)
        tgt_ids, tgt_vecs = fetch_sentences_with_vecs(conn, tgt_ed, tgt_body)

        log("align_sentences_loaded", src_count=len(src_ids), tgt_count=len(tgt_ids))

        pairs = dp_align(
            src_ids,
            src_vecs,
            tgt_ids,
            tgt_vecs,
            gap_penalty=args.gap_penalty,
            merge_penalty=args.merge_penalty,
        )

        method_name = f"{args.method}-{args.src}-{args.tgt}"
        kept = write_alignments(conn, work_id, src_ed, tgt_ed, pairs, method_name, args.min_score)
        log("align_done", kept=kept, method=method_name)
    finally:
        conn.close()
        log("align_connection_closed")


if __name__ == "__main__":
    main()
