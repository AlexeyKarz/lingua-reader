"""
Add-book helper for multilang-reader-mono.

Pipeline:
1) Ingest EPUB(s) through Spring Boot CLI (`./gradlew bootRun --args="ingest ..."`)
2) Embed any new sentences (`embed_sentences.py` reads DATABASE_URL / .env)
3) Align between two langs using DP aligner (`align_sentences_dp.py`)
4) Verify the work/editions are present in Postgres.

Assumptions:
- You already have the ingest CLI in the app
- DATABASE_URL is set (or present in .env) for the Python scripts
- Pgvector extension is available (embed script will CREATE EXTENSION if needed)

Examples:

python python-embed/add_book.py \
  --work "Faust (Part I)" --year 1806 \
  --edition "lang=de,file=classpath:books/faust_de_1.epub" \
  --edition "lang=en,file=classpath:books/faust_en_1.epub" \
  --align-src de --align-tgt en \
  --method dp-mlMiniLM-v1-de-en
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple, Any

import psycopg
from psycopg.rows import dict_row
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = Path(__file__).resolve().parent


def log(event: str, level: str = "INFO", **fields: Any) -> None:
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


def parse_kv_list(s: str) -> Dict[str, str]:
    """
    Parses: "lang=de,file=...,translator=..."
    """
    out: Dict[str, str] = {}
    # Allow commas inside quoted values by temporarily replacing them
    temp = []
    current = ""
    in_quote = False
    for ch in s:
        if ch in ("'", '"'):
            in_quote = not in_quote
        if ch == "," and in_quote:
            current += "\u001f"  # unit separator placeholder
        else:
            current += ch
    parts = [p.strip().replace("\u001f", ",") for p in current.split(",") if p.strip()]
    for p in parts:
        if "=" not in p:
            raise ValueError(f"Bad edition part '{p}'. Expected key=value.")
        k, v = p.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def resolve_file_arg(file_value: str) -> str:
    """
    Accepts:
      - classpath:books/x.epub
      - /abs/path/x.epub
      - relative/path/x.epub

    Returns the value unchanged if classpath is provided.
    Otherwise resolves to an absolute path if it exists; if not, tries
    src/main/resources/books/<basename> and returns a classpath:... URL
    when found so Spring's ResourceLoader can read it.
    """
    if file_value.startswith("classpath:"):
        return file_value

    p = Path(file_value)
    if p.exists():
        return str(p.resolve())

    # Try resolving relative to resources/books for convenience
    resources_books = ROOT / "src" / "main" / "resources" / "books" / p.name
    if resources_books.exists():
        return f"classpath:books/{resources_books.name}"

    raise SystemExit(f"File does not exist: {file_value}")


def gradle_ingest(work: str, year: Optional[int], author: Optional[str],
                 lang: str, file_arg: str, translator: Optional[str],
                 original_lang: Optional[str]) -> None:
    """
    Runs:
      ./gradlew bootRun --args='ingest --headless --work "..." --lang xx --file ... --year N [--author "..."] [--translator "..."]'
    """
    year_part = f' --year {year}' if year else ""
    author_part = f' --author "{author}"' if author else ""
    original_part = f' --original-lang "{original_lang}"' if original_lang else ""
    translator_part = f' --translator "{translator}"' if translator else ""

    # Build a single --args string
    args_str = (
        f'ingest --headless --work "{work}" --lang {lang} --file {file_arg}'
        f'{year_part}{author_part}{original_part}{translator_part}'
    )

    cmd = [
        str(ROOT / "gradlew"),
        "--no-daemon",
        "--console=plain",
        "bootRun",
        f"--args={args_str}"
    ]

    log("ingest_start", work=work, lang=lang, file=file_arg, year=year, author=author, original_lang=original_lang, translator=translator)
    log("ingest_cmd", cmd=" ".join(cmd))

    # Important: This will start Spring Boot per edition.
    # If port 8080 is in use, stop the running app first.
    res = subprocess.run(cmd, cwd=ROOT)

    if res.returncode != 0:
        log("ingest_failed", level="ERROR", work=work, lang=lang, returncode=res.returncode)
        raise RuntimeError("Gradle ingestion failed")

    # Optional: print a trimmed success tail
    if res.stdout:
        tail = "\n".join(res.stdout.splitlines()[-20:])
        log("ingest_output_tail", work=work, lang=lang, tail=tail)
    log("ingest_ok", work=work, lang=lang)


def get_conn():
    # Load .env if present (repo root and python-embed dir)
    load_dotenv(ROOT / ".env")
    load_dotenv(SCRIPTS_DIR / ".env")

    dsn = os.getenv("DATABASE_URL") or os.getenv("MLR_DB_DSN")
    if not dsn:
        host = os.getenv("MLR_DB_HOST")
        port = os.getenv("MLR_DB_PORT", "5432")
        name = os.getenv("MLR_DB_NAME")
        user = os.getenv("MLR_DB_USER")
        password = os.getenv("MLR_DB_PASS")
        if host and name and user:
            dsn = f"postgresql://{user}:{password or ''}@{host}:{port}/{name}"

    if not dsn:
        raise SystemExit("DATABASE_URL (or MLR_DB_DSN/MLR_DB_* env vars) must be set; .env not found or missing DATABASE_URL")
    return psycopg.connect(dsn, row_factory=dict_row)


def fetch_one(cur, sql: str, params: Tuple) -> Optional[Any]:
    cur.execute(sql, params)
    return cur.fetchone()


def fetch_all(cur, sql: str, params: Tuple) -> List[Any]:
    cur.execute(sql, params)
    return cur.fetchall()


def set_body_start(work: str, specs: List[Dict[str, str]]) -> None:
    """
    Given specs like {"lang": "es", "text": "Primera frase..."},
    find the first sentence containing that text and set edition.body_start_segment_id.
    """
    if not specs:
        return

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            work_row = fetch_one(cur, "select id from work where title = %s", (work,))
            if not work_row:
                raise AssertionError(f"Work not found when setting body_start: '{work}'")
            work_id = work_row["id"]

            for spec in specs:
                lang = spec.get("lang")
                text = spec.get("text")
                if not lang or not text:
                    log("body_start_skip", level="WARN", work=work, lang=lang, reason="missing_lang_or_text")
                    continue

                ed = fetch_one(
                    cur,
                    "select id from edition where work_id = %s and lang = %s order by id desc limit 1",
                    (work_id, lang),
                )
                if not ed:
                    log("body_start_no_edition", level="WARN", work=work, lang=lang)
                    continue
                ed_id = ed["id"]

                norm_fragment = re.sub(r"[^\w]+", "", text.lower())

                seg = fetch_one(
                    cur,
                    """
                    select s.id
                    from segment s
                    where s.edition_id = %s
                      and s.level = 'sentence'
                      and regexp_replace(lower(s.text), '[^[:alnum:]]+', '', 'g') like %s
                    order by s.id
                    limit 1
                    """,
                    (ed_id, f"%{norm_fragment}%"),
                )
                if not seg:
                    log("body_start_not_found", level="WARN", work=work, lang=lang, text=text)
                    continue

                seg_id = seg["id"]
                cur.execute(
                    "update edition set body_start_segment_id = %s where id = %s",
                    (seg_id, ed_id),
                )
                log("body_start_set", work=work, lang=lang, segment_id=seg_id)

        conn.commit()
    finally:
        conn.close()


def verify_work_and_editions(work: str, editions: List[Dict[str, str]]) -> None:
    """
    Verifies:
    - work exists
    - each edition exists
    - segments exist by level
    """
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            w = fetch_one(cur, "select id, title, author, pub_year from work where title = %s", (work,))
            if not w:
                raise AssertionError(f"Work not found in DB after ingestion: '{work}'")
            work_id = w["id"]

            log("verify_work", work_id=work_id, title=w["title"], year=w.get("pub_year"))

            for ed in editions:
                lang = ed.get("lang")
                translator = ed.get("translator")

                if translator:
                    e = fetch_one(
                            cur,
                            "select id, lang, translator from edition where work_id=%s and lang=%s and translator=%s",
                            (work_id, lang, translator),
                        )
                else:
                    # if translator not used in the schema/uniqueness, this should still find the edition
                    e = fetch_one(
                        cur,
                        "select id, lang, translator from edition where work_id=%s and lang=%s order by id desc limit 1",
                        (work_id, lang),
                    )

                if not e:
                    raise AssertionError(f"Edition not found for work='{work}', lang='{lang}'")

                edition_id = e["id"]
                log("verify_edition", edition_id=edition_id, lang=e["lang"], translator=e.get("translator"))

                levels = fetch_all(
                    cur,
                    """
                    select level, count(*) as cnt
                    from segment
                    where edition_id = %s
                    group by level
                    """,
                    (edition_id,),
                )
                level_map = {r["level"]: r["cnt"] for r in levels}

                ch = level_map.get("chapter", 0)
                pa = level_map.get("paragraph", 0)
                se = level_map.get("sentence", 0)

                log("verify_segments", edition_id=edition_id, chapters=ch, paragraphs=pa, sentences=se)

                # Basic sanity assertions
                if ch == 0:
                    raise AssertionError(f"No chapters ingested for edition {edition_id}")
                if pa == 0:
                    raise AssertionError(f"No paragraphs ingested for edition {edition_id}")
                if se == 0:
                    raise AssertionError(f"No sentences ingested for edition {edition_id}")

            log("verify_ok", work=work)

    finally:
        conn.close()


def reset_existing(work: str, langs: List[str]) -> None:
    """
    Delete existing segments/embeddings/alignments for given work/langs.
    Useful when re-running ingest to avoid duplicate segments.
    """
    if not langs:
        return

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            w = fetch_one(cur, "select id from work where title = %s", (work,))
            if not w:
                log("reset_skip", work=work, reason="work_not_found")
                return
            work_id = w["id"]

            cur.execute(
                """
                select id, lang
                from edition
                where work_id = %s and lang = any(%s)
                """,
                (work_id, langs),
            )
            editions = cur.fetchall()
            if not editions:
                log("reset_skip", work=work, langs=",".join(langs), reason="editions_not_found")
                return

            ed_ids = [e["id"] for e in editions]

            # Delete alignments where these editions are src or tgt
            cur.execute(
                """
                delete from alignment
                where work_id = %s
                  and (edition_src_id = any(%s) or edition_tgt_id = any(%s))
                """,
                (work_id, ed_ids, ed_ids),
            )

            # Delete embeddings and segments for these editions
            cur.execute(
                """
                delete from embedding
                where segment_id in (select id from segment where edition_id = any(%s))
                """,
                (ed_ids,),
            )
            cur.execute(
                "delete from segment where edition_id = any(%s)",
                (ed_ids,),
            )

            # Reset body_start_segment_id
            cur.execute(
                "update edition set body_start_segment_id = null where id = any(%s)",
                (ed_ids,),
            )

            conn.commit()
            log("reset_done", work=work, langs=",".join(langs))
    finally:
        conn.close()


def run_embed() -> None:
    """
    Runs embed_sentences.py to fill in missing embeddings.
    """
    cmd = [sys.executable, str(SCRIPTS_DIR / "embed_sentences.py")]
    log("embed_start", cmd=" ".join(cmd))
    res = subprocess.run(cmd, cwd=SCRIPTS_DIR)
    if res.returncode != 0:
        raise RuntimeError("Embedding step failed")
    log("embed_ok")


def run_align(work: str, src: str, tgt: str, method: Optional[str],
              aligner: str, threshold: float, window: int) -> None:
    """
    Runs DP or greedy aligner for the specified language pair.
    """
    if aligner == "dp":
        cmd = [
            sys.executable,
            str(SCRIPTS_DIR / "align_sentences_dp.py"),
            "--work",
            work,
            "--src",
            src,
            "--tgt",
            tgt,
        ]
        if method:
            cmd.extend(["--method", method])
    else:
        cmd = [
            sys.executable,
            str(SCRIPTS_DIR / "align_sentences.py"),
            "--work",
            work,
            "--src",
            src,
            "--tgt",
            tgt,
            "--threshold",
            str(threshold),
            "--window",
            str(window),
        ]
        if method:
            cmd.extend(["--method", method])

    log("align_start", cmd=" ".join(cmd), work=work, src=src, tgt=tgt, method=method, aligner=aligner)
    res = subprocess.run(cmd, cwd=SCRIPTS_DIR)
    if res.returncode != 0:
        raise RuntimeError("Alignment step failed")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--work", required=True)
    p.add_argument("--year", type=int, default=None)
    p.add_argument("--author", default=None, help="Author name for the work (applied to all editions)")
    p.add_argument("--original-lang", default=None, help="Preferred/original language code for the work")
    p.add_argument("--method", default=None, help="Alignment method tag (stored in alignment.method)")
    p.add_argument("--align-src", help="Lang code for source edition (default: first --edition)")
    p.add_argument("--align-tgt", help="Lang code for target edition (default: second --edition)")
    p.add_argument("--aligner", choices=["dp", "greedy"], default="dp", help="Alignment algorithm to use")
    p.add_argument("--threshold", type=float, default=0.6, help="Greedy aligner similarity threshold")
    p.add_argument("--window", type=int, default=10, help="Greedy aligner window size")
    p.add_argument(
        "--body-start",
        action="append",
        help='Body start spec: lang=es,text="First sentence text fragment"',
    )
    p.add_argument(
        "--reset-existing",
        action="store_true",
        help="Remove existing segments/embeddings/alignments for the specified work/langs before ingesting (prevents duplicate segments on re-run)",
    )

    # Repeatable editions
    p.add_argument(
        "--edition",
        action="append",
        required=True,
        help='Edition spec like: "lang=de,file=classpath:books/x.epub,translator=Name"',
    )

    args = p.parse_args()

    editions: List[Dict[str, str]] = []
    for ed_str in args.edition:
        ed = parse_kv_list(ed_str)
        if "lang" not in ed or "file" not in ed:
            raise SystemExit("Each --edition must include lang= and file=")
        editions.append(ed)

    if len(editions) < 2:
        raise SystemExit("Provide at least two --edition entries so we can align.")

    align_src = args.align_src or editions[0]["lang"]
    align_tgt = args.align_tgt or editions[1]["lang"]
    method = args.method or f"{args.aligner}-mlMiniLM-v1-{align_src}-{align_tgt}"

    log("add_book_start",
        work=args.work,
        author=args.author or "n/a",
        editions=",".join([e["lang"] for e in editions]),
        align_src=align_src,
        align_tgt=align_tgt,
        method=method,
        aligner=args.aligner)

    # Ingest each edition
    if args.reset_existing:
        log("reset_start", work=args.work)
        reset_existing(args.work, [e["lang"] for e in editions])

    for ed in editions:
        lang = ed["lang"]
        file_arg = resolve_file_arg(ed["file"])
        translator = ed.get("translator")

        gradle_ingest(args.work, args.year, args.author, lang, file_arg, translator, args.original_lang)

    # Optionally set body_start_segment_id based on provided text fragments
    body_specs: List[Dict[str, str]] = []
    if args.body_start:
        for spec in args.body_start:
            parsed = parse_kv_list(spec)
            body_specs.append(parsed)
    set_body_start(args.work, body_specs)


    # Embed any new sentences (idempotent)
    run_embed()

    # Align between the two langs of interest
    run_align(args.work, align_src, align_tgt, method, args.aligner, args.threshold, args.window)

    # Verify in DB
    verify_work_and_editions(args.work, editions)


if __name__ == "__main__":
    main()
