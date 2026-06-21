"""
Add-book pipeline script for multilang-reader-mono.

Pipeline:
1) Ingest EPUB(s) through running app ingestion jobs API (`POST /api/admin/ingest/jobs`)
2) Embed any new sentences (`embed_sentences.py` reads DATABASE_URL / .env)
3) Align between two langs using DP aligner (`align_sentences_dp.py`)
4) Verify the work/editions are present in Postgres.

Examples:

python python-embed/add_book.py \
  --work "Faust (Part I)" --year 1806 \
  --edition "lang=de,file=classpath:books/faust_de_1.epub" \
  --edition "lang=en,file=classpath:books/faust_en_1.epub" \
  --align-src de --align-tgt en \
  --method dp-mlMiniLM-v1-de-en
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
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


def default_api_base_url() -> str:
    # Keep API base URL loading behavior consistent with DATABASE_URL loading.
    load_dotenv(ROOT / ".env")
    load_dotenv(SCRIPTS_DIR / ".env")
    return os.getenv("MLR_API_BASE_URL", "http://localhost:8080")


def default_admin_token() -> Optional[str]:
    load_dotenv(ROOT / ".env")
    load_dotenv(SCRIPTS_DIR / ".env")
    token = os.getenv("MLR_ADMIN_TOKEN")
    return token if token else None


def _api_json(
    method: str,
    url: str,
    timeout_sec: float,
    payload: Optional[Dict[str, Any]] = None,
    admin_token: Optional[str] = None,
) -> Dict[str, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if admin_token:
        headers["Authorization"] = f"Bearer {admin_token}"
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url=url, method=method, headers=headers, data=data)
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            body = resp.read().decode("utf-8") if resp else ""
            if not body:
                return {}
            return json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        raise RuntimeError(f"HTTP {e.code} from {url}: {body}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"Cannot reach API {url}: {e.reason}") from e


def ingest_via_api(
    api_base_url: str,
    work: str,
    year: Optional[int],
    author: Optional[str],
    description: Optional[str],
    lang: str,
    file_arg: str,
    publisher: Optional[str],
    translator: Optional[str],
    original_lang: Optional[str],
    poll_interval_sec: float,
    timeout_sec: int,
    admin_token: Optional[str],
) -> None:
    base = api_base_url.rstrip("/")
    submit_url = f"{base}/api/admin/ingest/jobs"
    payload = {
        "workTitle": work,
        "lang": lang,
        "fileSpec": file_arg,
        "year": year,
        "author": author,
        "description": description,
        "publisher": publisher,
        "translator": translator,
        "originalLang": original_lang,
    }
    payload = {k: v for k, v in payload.items() if v is not None}

    log(
        "ingest_start",
        mode="api",
        api_base_url=base,
        work=work,
        lang=lang,
        file=file_arg,
        year=year,
        author=author,
        description=description,
        publisher=publisher,
        original_lang=original_lang,
        translator=translator,
    )

    submitted = _api_json("POST", submit_url, timeout_sec=30, payload=payload, admin_token=admin_token)
    job_id = submitted.get("jobId")
    if not isinstance(job_id, int):
        raise RuntimeError(f"Ingestion API response missing jobId: {submitted}")
    status_url = f"{base}/api/admin/ingest/jobs/{job_id}"
    log("ingest_job_submitted", work=work, lang=lang, job_id=job_id, status_url=status_url)

    started = time.monotonic()
    last_marker: Optional[Tuple[Any, ...]] = None

    while True:
        job = _api_json("GET", status_url, timeout_sec=30, admin_token=admin_token)
        status = job.get("status", "UNKNOWN")
        step = job.get("step")
        msg = job.get("message")
        chapters = job.get("chapters")
        paragraphs = job.get("paragraphs")
        sentences = job.get("sentences")
        error = job.get("error")
        marker = (status, step, msg, chapters, paragraphs, sentences, error)
        if marker != last_marker:
            log(
                "ingest_job_progress",
                work=work,
                lang=lang,
                job_id=job_id,
                status=status,
                step=step,
                message=msg,
                chapters=chapters,
                paragraphs=paragraphs,
                sentences=sentences,
                error=error,
            )
            last_marker = marker

        if status == "SUCCEEDED":
            log("ingest_ok", mode="api", work=work, lang=lang, job_id=job_id)
            return
        if status == "FAILED":
            raise RuntimeError(f"Ingestion job {job_id} failed for {work} [{lang}]: {error or msg}")

        elapsed = time.monotonic() - started
        if elapsed > timeout_sec:
            raise RuntimeError(
                f"Ingestion job {job_id} timed out after {timeout_sec}s "
                f"for {work} [{lang}] (last status={status}, step={step})"
            )
        time.sleep(max(0.2, poll_interval_sec))


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


def build_alignment_plan(
    editions: List[Dict[str, str]],
    align_src_arg: Optional[str],
    align_tgt_arg: Optional[str],
    original_lang_arg: Optional[str],
    aligner: str,
    method_arg: Optional[str],
) -> List[Tuple[str, str, str]]:
    langs = [e["lang"] for e in editions]
    unique_langs_in_order = list(dict.fromkeys(langs))

    # Aligners currently resolve editions by (work, lang), so duplicate langs in one run are ambiguous.
    if len(unique_langs_in_order) != len(langs):
        raise SystemExit(
            "Duplicate language entries in --edition are not supported in one run. "
            "Use one edition per language."
        )

    src = (align_src_arg or original_lang_arg or "").strip().lower()
    if not src:
        if len(unique_langs_in_order) > 2:
            raise SystemExit(
                "For 3+ editions, specify source language explicitly via --original-lang or --align-src."
            )
        src = unique_langs_in_order[0]

    if src not in unique_langs_in_order:
        raise SystemExit(f"Source language '{src}' is not present in --edition list: {','.join(unique_langs_in_order)}")

    if align_tgt_arg:
        tgt = align_tgt_arg.strip().lower()
        if tgt == src:
            raise SystemExit("--align-tgt cannot be the same as source language")
        if tgt not in unique_langs_in_order:
            raise SystemExit(f"Target language '{tgt}' is not present in --edition list: {','.join(unique_langs_in_order)}")
        targets = [tgt]
    else:
        targets = [lang for lang in unique_langs_in_order if lang != src]

    if not targets:
        raise SystemExit("No target languages available for alignment.")

    plan: List[Tuple[str, str, str]] = []
    for tgt in targets:
        if method_arg:
            if len(targets) > 1 and ("{src}" in method_arg or "{tgt}" in method_arg):
                method = method_arg.format(src=src, tgt=tgt)
            elif len(targets) > 1:
                raise SystemExit(
                    "When aligning multiple targets, --method must contain {src} and {tgt}, "
                    "or omit --method to auto-generate method names."
                )
            else:
                method = method_arg
        else:
            method = f"{aligner}-mlMiniLM-v1-{src}-{tgt}"
        plan.append((src, tgt, method))

    return plan


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--work", required=True)
    p.add_argument("--year", type=int, default=None)
    p.add_argument("--author", default=None, help="Author name for the work (applied to all editions)")
    p.add_argument("--description", default=None, help="Short catalog description for the work")
    p.add_argument("--original-lang", default=None, help="Preferred/original language code for the work")
    p.add_argument(
        "--api-base-url",
        default=default_api_base_url(),
        help="Running monolith base URL for ingestion API (default: http://localhost:8080 or MLR_API_BASE_URL)",
    )
    p.add_argument(
        "--admin-token",
        default=default_admin_token(),
        help="Bearer token for secured admin ingestion API (default: MLR_ADMIN_TOKEN from .env/env)",
    )
    p.add_argument(
        "--poll-interval-sec",
        type=float,
        default=2.0,
        help="Polling interval for ingestion job status",
    )
    p.add_argument(
        "--ingest-timeout-sec",
        type=int,
        default=3600,
        help="Per-edition ingestion timeout in seconds",
    )
    p.add_argument(
        "--method",
        default=None,
        help="Alignment method tag. For multi-target runs, use placeholders {src} and {tgt}, e.g. dp-mlMiniLM-v1-{src}-{tgt}",
    )
    p.add_argument("--align-src", help="Lang code for source edition (default: first --edition)")
    p.add_argument(
        "--align-tgt",
        help="Lang code for a single target edition. If omitted, aligns source -> all other languages",
    )
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
        ed["lang"] = ed["lang"].strip().lower()
        editions.append(ed)

    if len(editions) < 2:
        raise SystemExit("Provide at least two --edition entries so we can align.")

    align_plan = build_alignment_plan(
        editions=editions,
        align_src_arg=args.align_src,
        align_tgt_arg=args.align_tgt,
        original_lang_arg=args.original_lang,
        aligner=args.aligner,
        method_arg=args.method,
    )
    align_src = align_plan[0][0]
    align_tgts = [t for (_, t, _) in align_plan]

    log("add_book_start",
        work=args.work,
        author=args.author or "n/a",
        description=args.description or "n/a",
        editions=",".join([e["lang"] for e in editions]),
        api_base_url=args.api_base_url,
        align_src=align_src,
        align_tgts=",".join(align_tgts),
        align_pairs=",".join([f"{s}->{t}" for (s, t, _) in align_plan]),
        methods=",".join([m for (_, _, m) in align_plan]),
        aligner=args.aligner)

    # Ingest each edition
    if args.reset_existing:
        log("reset_start", work=args.work)
        reset_existing(args.work, list(dict.fromkeys([e["lang"] for e in editions])))

    for ed in editions:
        lang = ed["lang"]
        file_arg = resolve_file_arg(ed["file"])
        publisher = ed.get("publisher")
        translator = ed.get("translator")

        ingest_via_api(
            api_base_url=args.api_base_url,
            work=args.work,
            year=args.year,
            author=args.author,
            description=args.description,
            lang=lang,
            file_arg=file_arg,
            publisher=publisher,
            translator=translator,
            original_lang=args.original_lang,
            poll_interval_sec=args.poll_interval_sec,
            timeout_sec=args.ingest_timeout_sec,
            admin_token=args.admin_token,
        )

    # Optionally set body_start_segment_id based on provided text fragments
    body_specs: List[Dict[str, str]] = []
    if args.body_start:
        for spec in args.body_start:
            parsed = parse_kv_list(spec)
            body_specs.append(parsed)
    set_body_start(args.work, body_specs)


    # Embed any new sentences (idempotent)
    run_embed()

    # Align source -> each target from plan
    for src, tgt, method in align_plan:
        run_align(args.work, src, tgt, method, args.aligner, args.threshold, args.window)

    # Verify in DB
    verify_work_and_editions(args.work, editions)


if __name__ == "__main__":
    main()
