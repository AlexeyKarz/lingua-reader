#!/usr/bin/env python3
"""
Align sentence-level segments between two editions of the same work
using precomputed sentence embeddings (pgvector) and a greedy,
monotonic, windowed algorithm.

Usage example:

    source .venv/bin/activate
    export DATABASE_URL="postgresql://mlr:mlr_password@localhost:5432/mlr"

    python align_sentences.py \
        --work "Faust (Part I)" \
        --src de \
        --tgt en \
        --method greedy-mlMiniLM-v1 \
        --threshold 0.6 \
        --window 10
"""

import os
import argparse
from typing import List, Tuple

import numpy as np
import psycopg
from dotenv import load_dotenv
from pgvector.psycopg import register_vector


# -------------------------
# DB connection helper
# -------------------------

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


def fetch_sentences_with_vecs(conn, edition_id: int) -> Tuple[List[int], np.ndarray]:
    """
    Returns (segment_ids, vectors) for all sentence-level segments in order
    for a given edition. Requires embedding rows to exist for those segments.
    """
    sql = """
        select s.id, e.vec
        from segment s
        join embedding e on e.segment_id = s.id
        where s.edition_id = %s
          and s.level = 'sentence'
        order by s.id
    """
    with conn.cursor() as cur:
        cur.execute(sql, (edition_id,))
        rows = cur.fetchall()

    if not rows:
        raise RuntimeError(f"No sentence+embedding rows found for edition_id={edition_id}")

    ids = [int(r[0]) for r in rows]
    # pgvector returns something list-like; convert each to np.array then stack
    vecs = np.stack([np.array(r[1], dtype=float) for r in rows])

    # L2-normalize (if not already normalized)
    norms = np.linalg.norm(vecs, axis=1, keepdims=True) + 1e-9
    vecs = vecs / norms

    return ids, vecs


# -------------------------
# Greedy windowed alignment
# -------------------------

def greedy_align(
        src_ids: List[int],
        src_vecs: np.ndarray,
        tgt_ids: List[int],
        tgt_vecs: np.ndarray,
        sim_threshold: float,
        window_size: int,
        big_fail_threshold: int = 50,
        big_skip: int = 20,
) -> List[Tuple[int, int, float]]:
    """
    Greedy, monotonic, windowed alignment with a simple heuristic to
    skip ahead in the target after many consecutive unmatched source sentences.

    src_* = source language (e.g. DE)
    tgt_* = target language (e.g. EN)

    Returns list of (src_segment_id, tgt_segment_id, score).
    """

    i = 0  # index in src
    j = 0  # index in tgt (search start)
    n_src = len(src_ids)
    n_tgt = len(tgt_ids)

    results: List[Tuple[int, int, float]] = []
    fail_count = 0

    while i < n_src and j < n_tgt:
        src_vec = src_vecs[i]

        start = j
        end = min(n_tgt, j + window_size)
        if start >= end:
            break

        # Cosine similarity since vectors normalized: dot product
        sims = np.dot(tgt_vecs[start:end], src_vec)
        best_local_idx = int(np.argmax(sims))
        best_sim = float(sims[best_local_idx])
        best_j = start + best_local_idx

        if best_sim >= sim_threshold:
            # Accept alignment
            src_seg_id = src_ids[i]
            tgt_seg_id = tgt_ids[best_j]
            results.append((src_seg_id, tgt_seg_id, best_sim))

            # Move target pointer just after the best match
            j = best_j + 1
            fail_count = 0
        else:
            # No good match in window; mark this src as unaligned
            fail_count += 1

            # If many consecutive failures, skip ahead in target to try to jump over
            # a long extra section (e.g. translator foreword).
            if fail_count >= big_fail_threshold:
                j = min(n_tgt, j + big_skip)
                fail_count = 0

        i += 1

    return results


# -------------------------
# Write alignments to DB
# -------------------------

def write_alignments(
        conn,
        work_id: int,
        src_edition_id: int,
        tgt_edition_id: int,
        pairs: List[Tuple[int, int, float]],
        method_name: str,
):
    """
    Insert / upsert alignments into the existing alignment table:

        id, left_segment_id, right_segment_id, method, score, created_at,
        work_id, edition_src_id, edition_tgt_id

    Uses the UNIQUE(left_segment_id, right_segment_id, method) constraint.
    """
    if not pairs:
        print("No pairs to write.")
        return

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

    with conn.cursor() as cur:
        for src_seg_id, tgt_seg_id, score in pairs:
            cur.execute(
                sql,
                (
                    src_seg_id,
                    tgt_seg_id,
                    method_name,
                    score,
                    work_id,
                    src_edition_id,
                    tgt_edition_id,
                ),
            )
    conn.commit()


# -------------------------
# Main CLI
# -------------------------

def parse_args():
    p = argparse.ArgumentParser(description="Align sentence segments between two editions.")
    p.add_argument("--work", required=True, help="Work title, e.g. 'Faust (Part I)'")
    p.add_argument("--src", required=True, help="Source language code, e.g. 'de'")
    p.add_argument("--tgt", required=True, help="Target language code, e.g. 'en'")
    p.add_argument(
        "--method",
        default="greedy-mlMiniLM-v1",
        help="Method tag stored in alignment.method (default: greedy-mlMiniLM-v1)",
    )
    p.add_argument(
        "--threshold",
        type=float,
        default=0.6,
        help="Cosine similarity threshold for accepting an alignment (default: 0.6)",
    )
    p.add_argument(
        "--window",
        type=int,
        default=10,
        help="Target window size to search for matches (default: 10)",
    )
    p.add_argument(
        "--big-fail-threshold",
        type=int,
        default=50,
        help="Consecutive unmatched src sentences before skipping ahead in target (default: 50)",
    )
    p.add_argument(
        "--big-skip",
        type=int,
        default=20,
        help="Target sentences to skip when big-fail-threshold is reached (default: 20)",
    )
    return p.parse_args()


def main():
    args = parse_args()

    conn = get_conn()
    try:
        work_id, src_ed, tgt_ed = fetch_edition_ids(conn, args.work, args.src, args.tgt)
        print(
            f"Aligning work='{args.work}' (id={work_id}) "
            f"{args.src} edition {src_ed} → {args.tgt} edition {tgt_ed}"
        )

        src_ids, src_vecs = fetch_sentences_with_vecs(conn, src_ed)
        tgt_ids, tgt_vecs = fetch_sentences_with_vecs(conn, tgt_ed)
        print(f"Loaded {len(src_ids)} source sentences, {len(tgt_ids)} target sentences")

        pairs = greedy_align(
            src_ids,
            src_vecs,
            tgt_ids,
            tgt_vecs,
            sim_threshold=args.threshold,
            window_size=args.window,
            big_fail_threshold=args.big_fail_threshold,
            big_skip=args.big_skip,
        )
        print(f"Aligned {len(pairs)} sentence pairs (score >= {args.threshold})")

        # Method name includes langs for clarity if you want
        method_name = f"{args.method}-{args.src}-{args.tgt}"
        write_alignments(conn, work_id, src_ed, tgt_ed, pairs, method_name)
        print("Alignments written to DB.")
    finally:
        conn.close()
        print("Connection closed.")


if __name__ == "__main__":
    main()
