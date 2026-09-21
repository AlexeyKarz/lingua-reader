"""
Add-book pipeline script for multilang-reader-mono.

Pipeline:
1) Ingest EPUB(s) through running app ingestion jobs API (`POST /api/admin/ingest/jobs`)
2) Import missing local dictionary entries for the source-language book terms
3) Embed any new sentences (`embed_sentences.py` reads DATABASE_URL / .env)
4) Align between two langs using DP aligner (`align_sentences_dp.py`)
5) Verify the work/editions are present in Postgres.

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
import mimetypes
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple, Any

import psycopg
from psycopg.rows import dict_row
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = Path(__file__).resolve().parent
LANGUAGE_NAMES = {
    "de": "German",
    "en": "English",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian",
    "nl": "Dutch",
    "pl": "Polish",
    "pt": "Portuguese",
    "ru": "Russian",
}
DEFAULT_GENERATED_TRANSLATOR = "AI literary translation"


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


def parse_bool(value: Optional[str], default: bool = False) -> bool:
    if value is None or value == "":
        return default
    token = value.strip().lower()
    if token in {"1", "true", "t", "yes", "y", "on"}:
        return True
    if token in {"0", "false", "f", "no", "n", "off"}:
        return False
    raise ValueError(f"Expected boolean value, got '{value}'")


def parse_int(value: Optional[str], default: int, field: str) -> int:
    if value is None or value == "":
        return default
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"Expected integer for {field}, got '{value}'") from exc
    if parsed < 0:
        raise ValueError(f"{field} must be >= 0")
    return parsed


def optional_int(value: Optional[str], field: str) -> Optional[int]:
    if value is None or value == "":
        return None
    return parse_int(value, 0, field)


def resolve_file_arg(file_value: str) -> str:
    """
    Accepts:
      - classpath:books/x.epub
      - file:/uploaded/path/x.epub
      - /abs/path/x.epub
      - relative/path/x.epub

    Returns the value unchanged if classpath is provided.
    Resource URLs are passed through to the backend. Local EPUB/TXT paths are
    resolved to absolute paths and uploaded through the admin upload endpoint.
    """
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", file_value):
        return file_value

    p = Path(file_value).expanduser()
    ingestion_dir = Path(os.getenv("INGESTION_DIR", "/ingestion"))
    candidates = [p] if p.is_absolute() else [
        Path.cwd() / p,
        ROOT / p,
        SCRIPTS_DIR / p,
        ingestion_dir / p,
        ROOT / "ops" / "ingestion" / p,
    ]
    for candidate in candidates:
        if candidate.exists() and candidate.is_file():
            return str(candidate.resolve())

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
    token = os.getenv("MLR_ADMIN_API_TOKEN") or os.getenv("MLR_ADMIN_TOKEN")
    return token if token else None


def default_dictionary_dir() -> str:
    load_dotenv(ROOT / ".env")
    load_dotenv(SCRIPTS_DIR / ".env")
    return os.getenv("DICTIONARY_DIR", "/dictionaries")


def resolve_local_dir(dir_value: str) -> Path:
    path = Path(dir_value).expanduser()
    if path.is_absolute():
        return path

    candidates = [
        ROOT / path,
        Path.cwd() / path,
        SCRIPTS_DIR / path,
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0]


def resolve_configured_dictionary_file(file_value: str, dictionary_dir: Path) -> Path:
    path = Path(file_value).expanduser()
    candidates = [path] if path.is_absolute() else [
        Path.cwd() / path,
        ROOT / path,
        SCRIPTS_DIR / path,
        dictionary_dir / path,
    ]

    seen: set[Path] = set()
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        if candidate.exists() and candidate.is_file():
            return candidate.resolve()

    searched = ", ".join(str(candidate) for candidate in candidates)
    raise SystemExit(f"Dictionary file not found: {file_value}. Searched: {searched}")


def resolve_dictionary_jsonl(
    dictionary_dir_arg: str,
    source_lang: str,
    configured_file: Optional[str],
    required: bool,
) -> Optional[Path]:
    dictionary_dir = resolve_local_dir(dictionary_dir_arg)
    if configured_file:
        return resolve_configured_dictionary_file(configured_file, dictionary_dir)

    if not dictionary_dir.exists():
        if required:
            raise SystemExit(f"Dictionary directory not found: {dictionary_dir}")
        return None

    language_name = LANGUAGE_NAMES.get(source_lang)
    base_names = [source_lang, f"{source_lang}-dictionary"]
    if language_name:
        base_names.extend([
            f"kaikki.org-dictionary-{language_name}",
            language_name,
            language_name.lower(),
        ])

    for base_name in base_names:
        for suffix in (".jsonl.gz", ".jsonl"):
            candidate = dictionary_dir / f"{base_name}{suffix}"
            if candidate.exists() and candidate.is_file():
                return candidate.resolve()

    glob_patterns = []
    if language_name:
        glob_patterns.extend([f"*{language_name}*.jsonl*", f"*{language_name.lower()}*.jsonl*"])
    glob_patterns.append(f"*{source_lang}*.jsonl*")
    for pattern in glob_patterns:
        matches = sorted(path for path in dictionary_dir.glob(pattern) if path.is_file())
        if matches:
            return matches[0].resolve()

    if required:
        expected = f"kaikki.org-dictionary-{language_name or source_lang}.jsonl"
        raise SystemExit(f"No dictionary JSONL found for source={source_lang} in {dictionary_dir} (expected {expected})")
    return None


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


def encode_multipart_form(fields: Dict[str, str], file_field: str, file_path: Path) -> Tuple[bytes, str]:
    boundary = f"----MLRBoundary{uuid.uuid4().hex}"
    chunks: List[bytes] = []

    for name, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode("utf-8"))
        chunks.append(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"))
        chunks.append(str(value).encode("utf-8"))
        chunks.append(b"\r\n")

    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    filename = re.sub(r'[\r\n"]+', "-", file_path.name)
    chunks.append(f"--{boundary}\r\n".encode("utf-8"))
    chunks.append(
        (
            f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
    )
    chunks.append(file_path.read_bytes())
    chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def _api_multipart(
    url: str,
    timeout_sec: float,
    fields: Dict[str, str],
    file_path: Path,
    admin_token: Optional[str],
) -> Dict[str, Any]:
    data, content_type = encode_multipart_form(fields, "file", file_path)
    headers = {
        "Accept": "application/json",
        "Content-Type": content_type,
        "Content-Length": str(len(data)),
    }
    if admin_token:
        headers["Authorization"] = f"Bearer {admin_token}"

    req = urllib.request.Request(url=url, method="POST", headers=headers, data=data)
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


def poll_ingestion_job(
    api_base_url: str,
    work: str,
    lang: str,
    job_id: int,
    poll_interval_sec: float,
    timeout_sec: int,
    admin_token: Optional[str],
    mode: str,
) -> None:
    base = api_base_url.rstrip("/")
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
            log("ingest_ok", mode=mode, work=work, lang=lang, job_id=job_id)
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
    poll_ingestion_job(base, work, lang, job_id, poll_interval_sec, timeout_sec, admin_token, mode="api")


def ingest_upload_via_api(
    api_base_url: str,
    work: str,
    year: Optional[int],
    author: Optional[str],
    description: Optional[str],
    lang: str,
    file_path: Path,
    publisher: Optional[str],
    translator: Optional[str],
    original_lang: Optional[str],
    poll_interval_sec: float,
    timeout_sec: int,
    admin_token: Optional[str],
) -> None:
    base = api_base_url.rstrip("/")
    submit_url = f"{base}/api/admin/ingest/jobs/upload"
    fields: Dict[str, str] = {
        "workTitle": work,
        "lang": lang,
    }
    for key, value in {
        "year": year,
        "author": author,
        "description": description,
        "publisher": publisher,
        "translator": translator,
        "originalLang": original_lang,
    }.items():
        if value is not None and str(value).strip():
            fields[key] = str(value).strip()

    log(
        "ingest_start",
        mode="upload",
        api_base_url=base,
        work=work,
        lang=lang,
        file=file_path,
        year=year,
        author=author,
        description=description,
        publisher=publisher,
        original_lang=original_lang,
        translator=translator,
    )

    submitted = _api_multipart(submit_url, timeout_sec=120, fields=fields, file_path=file_path, admin_token=admin_token)
    job_id = submitted.get("jobId")
    if not isinstance(job_id, int):
        raise RuntimeError(f"Ingestion upload API response missing jobId: {submitted}")
    poll_ingestion_job(base, work, lang, job_id, poll_interval_sec, timeout_sec, admin_token, mode="upload")


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


def split_text_sentences(text: str) -> List[str]:
    cleaned = re.sub(r"\s+", " ", text or "").strip()
    if not cleaned:
        return []
    parts = re.split(r"(?<=[.!?;:…])\s+", cleaned)
    sentences = [part.strip() for part in parts if part.strip()]
    return sentences or [cleaned]


def chunk_text(text: str, max_chars: int) -> List[str]:
    cleaned = re.sub(r"\s+", " ", text or "").strip()
    if not cleaned:
        return []
    if max_chars <= 0 or len(cleaned) <= max_chars:
        return [cleaned]

    chunks: List[str] = []
    current: List[str] = []
    current_len = 0
    for sentence in split_text_sentences(cleaned):
        if len(sentence) > max_chars:
            if current:
                chunks.append(" ".join(current).strip())
                current = []
                current_len = 0
            words = sentence.split()
            word_chunk: List[str] = []
            word_len = 0
            for word in words:
                added = len(word) + (1 if word_chunk else 0)
                if word_chunk and word_len + added > max_chars:
                    chunks.append(" ".join(word_chunk).strip())
                    word_chunk = [word]
                    word_len = len(word)
                else:
                    word_chunk.append(word)
                    word_len += added
            if word_chunk:
                chunks.append(" ".join(word_chunk).strip())
            continue

        added = len(sentence) + (1 if current else 0)
        if current and current_len + added > max_chars:
            chunks.append(" ".join(current).strip())
            current = [sentence]
            current_len = len(sentence)
        else:
            current.append(sentence)
            current_len += added

    if current:
        chunks.append(" ".join(current).strip())
    return chunks


def parse_generated_editions(
    raw_specs: Optional[List[str]],
    source_default: str,
    defaults: Dict[str, int],
) -> List[Dict[str, Any]]:
    generated: List[Dict[str, Any]] = []
    for raw in raw_specs or []:
        spec = parse_kv_list(raw)
        lang = (dictionary_spec_value(spec, "lang", "target", "target-lang", "target_lang") or "").strip().lower()
        if not lang:
            raise SystemExit("Each --generate-edition must include lang= or target=")
        source = (dictionary_spec_value(spec, "source", "source-lang", "source_lang") or source_default).strip().lower()
        if not source:
            raise SystemExit("Each --generate-edition needs source=, --align-src, or --original-lang")
        if source == lang:
            raise SystemExit(f"Generated edition source and target cannot match: {source}->{lang}")

        generated.append({
            "lang": lang,
            "source": source,
            "publisher": dictionary_spec_value(spec, "publisher"),
            "translator": dictionary_spec_value(spec, "translator") or DEFAULT_GENERATED_TRANSLATOR,
            "year": optional_int(dictionary_spec_value(spec, "year"), "generated edition year"),
            "max_source_chars": parse_int(
                dictionary_spec_value(spec, "max-source-chars", "max_source_chars"),
                defaults["max_source_chars"],
                "max-source-chars",
            ),
            "chunk_max_chars": parse_int(
                dictionary_spec_value(spec, "chunk-max-chars", "chunk_max_chars"),
                defaults["chunk_max_chars"],
                "chunk-max-chars",
            ),
            "context_chars": parse_int(
                dictionary_spec_value(spec, "context-chars", "context_chars"),
                defaults["context_chars"],
                "context-chars",
            ),
            "translate_timeout_sec": parse_int(
                dictionary_spec_value(spec, "translate-timeout-sec", "translate_timeout_sec"),
                defaults["translate_timeout_sec"],
                "translate-timeout-sec",
            ),
            "replace_existing": parse_bool(dictionary_spec_value(spec, "replace", "replace-existing"), True),
        })
    return generated


def fetch_source_paragraphs(conn, work: str, source_lang: str, body_only: bool) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    with conn.cursor() as cur:
        cur.execute(
            """
            select w.id as work_id, w.title, w.author, w.description, w.pub_year,
                   e.id as edition_id, e.body_start_segment_id
            from work w
            join edition e on e.work_id = w.id
            where w.title = %s
              and e.lang = %s
            order by e.id desc
            limit 1
            """,
            (work, source_lang),
        )
        meta = cur.fetchone()
        if not meta:
            raise RuntimeError(f"Source edition not found for generated translation: work='{work}' source='{source_lang}'")

        body_start = meta["body_start_segment_id"] if body_only else None
        cur.execute(
            """
            select ch.id as chapter_id,
                   ch.ord as chapter_ord,
                   ch.text as chapter_title,
                   p.id as paragraph_id,
                   p.ord as paragraph_ord,
                   p.text as paragraph_text
            from segment p
            left join segment ch on ch.id = p.parent_id and ch.level = 'chapter'
            where p.edition_id = %s
              and p.level = 'paragraph'
              and (
                %s is null
                or exists (
                  select 1
                  from segment s
                  where s.parent_id = p.id
                    and s.level = 'sentence'
                    and s.id >= %s
                )
              )
            order by coalesce(ch.ord, 0), p.ord, p.id
            """,
            (meta["edition_id"], body_start, body_start),
        )
        paragraphs = cur.fetchall()
    return meta, paragraphs


def upsert_generated_edition(
    conn,
    work_id: int,
    lang: str,
    publisher: Optional[str],
    translator: str,
    year: Optional[int],
    source_uri: str,
    replace_existing: bool,
) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """
            select id
            from edition
            where work_id = %s
              and lang = %s
              and translator = %s
            order by id desc
            limit 1
            """,
            (work_id, lang, translator),
        )
        row = cur.fetchone()
        if row:
            edition_id = int(row["id"])
            if replace_existing:
                delete_edition_content(cur, work_id, edition_id)
            cur.execute(
                """
                update edition
                set publisher = coalesce(%s, publisher),
                    year = coalesce(%s, year),
                    source_uri = %s,
                    format = 'ai-literary'
                where id = %s
                """,
                (publisher, year, source_uri, edition_id),
            )
            return edition_id

        cur.execute(
            """
            insert into edition(work_id, lang, publisher, translator, year, source_uri, format)
            values (%s, %s, %s, %s, %s, %s, 'ai-literary')
            returning id
            """,
            (work_id, lang, publisher, translator, year, source_uri),
        )
        return int(cur.fetchone()["id"])


def delete_edition_content(cur, work_id: int, edition_id: int) -> None:
    cur.execute(
        """
        delete from alignment
        where work_id = %s
          and (edition_src_id = %s or edition_tgt_id = %s)
        """,
        (work_id, edition_id, edition_id),
    )
    cur.execute(
        """
        delete from embedding
        where segment_id in (select id from segment where edition_id = %s)
        """,
        (edition_id,),
    )
    cur.execute("delete from segment where edition_id = %s", (edition_id,))
    cur.execute("update edition set body_start_segment_id = null where id = %s", (edition_id,))


def insert_segment(cur, edition_id: int, level: str, ord_value: int, parent_id: Optional[int], text: str, cfi: str) -> int:
    cur.execute(
        """
        insert into segment(edition_id, level, ord, parent_id, text, cfi)
        values (%s, cast(%s as segment_level), %s, %s, %s, %s)
        returning id
        """,
        (edition_id, level, ord_value, parent_id, text, cfi),
    )
    return int(cur.fetchone()["id"])


def call_admin_literary_translation(
    api_base_url: str,
    admin_token: Optional[str],
    text: str,
    source: str,
    target: str,
    context: str,
    timeout_sec: int,
) -> str:
    url = f"{api_base_url.rstrip('/')}/api/admin/translate/literary"
    payload = {
        "text": text,
        "source": source,
        "target": target,
        "context": context,
    }
    response = _api_json("POST", url, timeout_sec=timeout_sec, payload=payload, admin_token=admin_token)
    translated = str(response.get("translated") or "").strip()
    if not translated:
        raise RuntimeError("Admin literary translation returned an empty response")
    return translated


def estimate_generated_edition(conn, work: str, spec: Dict[str, Any], body_only: bool) -> Tuple[int, int, int]:
    _, paragraphs = fetch_source_paragraphs(conn, work, spec["source"], body_only)
    total_chars = sum(len(row["paragraph_text"] or "") for row in paragraphs)
    chunks = sum(len(chunk_text(row["paragraph_text"] or "", spec["chunk_max_chars"])) for row in paragraphs)
    return len(paragraphs), total_chars, chunks


def run_generated_editions(
    work: str,
    year: Optional[int],
    specs: List[Dict[str, Any]],
    api_base_url: str,
    admin_token: Optional[str],
    body_only: bool,
    dry_run: bool,
) -> None:
    if not specs:
        return

    conn = get_conn()
    try:
        for spec in specs:
            source = spec["source"]
            target = spec["lang"]
            meta, paragraphs = fetch_source_paragraphs(conn, work, source, body_only)
            total_chars = sum(len(row["paragraph_text"] or "") for row in paragraphs)
            total_chunks = sum(len(chunk_text(row["paragraph_text"] or "", spec["chunk_max_chars"])) for row in paragraphs)
            log(
                "generate_literary_estimate",
                work=work,
                source=source,
                target=target,
                paragraphs=len(paragraphs),
                source_chars=total_chars,
                chunks=total_chunks,
                chunk_max_chars=spec["chunk_max_chars"],
            )
            if not paragraphs:
                raise RuntimeError(f"No source paragraphs available for generated translation: work='{work}' source='{source}'")
            if total_chars > spec["max_source_chars"]:
                raise RuntimeError(
                    f"Generated translation source text is too large for {work} {source}->{target}: "
                    f"{total_chars} > {spec['max_source_chars']} chars"
                )
            if dry_run:
                continue

            source_uri = f"ai-literary://{source}/{target}/{int(meta['work_id'])}"
            edition_id = upsert_generated_edition(
                conn,
                int(meta["work_id"]),
                target,
                spec["publisher"],
                spec["translator"],
                spec["year"] if spec["year"] is not None else year,
                source_uri,
                spec["replace_existing"],
            )

            current_chapter_key: Optional[Tuple[int, str]] = None
            target_chapter_id: Optional[int] = None
            first_sentence_id: Optional[int] = None
            previous_translation = ""

            for index, row in enumerate(paragraphs, start=1):
                chapter_ord = int(row["chapter_ord"] or 1)
                chapter_title = str(row["chapter_title"] or f"Chapter {chapter_ord}")
                chapter_key = (chapter_ord, chapter_title)
                if current_chapter_key != chapter_key:
                    with conn.cursor() as cur:
                        target_chapter_id = insert_segment(
                            cur,
                            edition_id,
                            "chapter",
                            chapter_ord,
                            None,
                            chapter_title,
                            f"ai-literary:chapter:{row['chapter_id'] or chapter_ord}",
                        )
                    current_chapter_key = chapter_key

                source_text = str(row["paragraph_text"] or "").strip()
                chunks = chunk_text(source_text, spec["chunk_max_chars"])
                translated_chunks: List[str] = []
                for chunk_index, chunk in enumerate(chunks, start=1):
                    context_parts = [
                        f"Work: {work}",
                        f"Chapter: {chapter_title}",
                        f"Paragraph: {index}/{len(paragraphs)}",
                    ]
                    if len(chunks) > 1:
                        context_parts.append(f"Paragraph chunk: {chunk_index}/{len(chunks)}")
                    if previous_translation and spec["context_chars"] > 0:
                        context_parts.append("Previous translated paragraph: " + previous_translation[-spec["context_chars"]:])
                    context = "\n".join(context_parts)
                    translated_chunks.append(
                        call_admin_literary_translation(
                            api_base_url,
                            admin_token,
                            chunk,
                            source,
                            target,
                            context,
                            spec["translate_timeout_sec"],
                        )
                    )

                translated_paragraph = " ".join(translated_chunks).strip()
                if not translated_paragraph:
                    raise RuntimeError(f"Generated empty paragraph for {work} {source}->{target} paragraph {index}")

                with conn.cursor() as cur:
                    paragraph_id = insert_segment(
                        cur,
                        edition_id,
                        "paragraph",
                        int(row["paragraph_ord"]),
                        target_chapter_id,
                        translated_paragraph,
                        f"ai-literary:paragraph:{row['paragraph_id']}",
                    )
                    for sent_ord, sentence in enumerate(split_text_sentences(translated_paragraph), start=1):
                        sentence_id = insert_segment(
                            cur,
                            edition_id,
                            "sentence",
                            sent_ord,
                            paragraph_id,
                            sentence,
                            f"ai-literary:sentence:{row['paragraph_id']}:{sent_ord}",
                        )
                        if first_sentence_id is None:
                            first_sentence_id = sentence_id

                previous_translation = translated_paragraph
                if index % 20 == 0 or index == len(paragraphs):
                    conn.commit()
                    log(
                        "generate_literary_progress",
                        work=work,
                        source=source,
                        target=target,
                        paragraphs=index,
                        total=len(paragraphs),
                    )

            if first_sentence_id is not None:
                with conn.cursor() as cur:
                    cur.execute(
                        "update edition set body_start_segment_id = %s where id = %s",
                        (first_sentence_id, edition_id),
                    )
            conn.commit()
            log(
                "generate_literary_ok",
                work=work,
                source=source,
                target=target,
                edition_id=edition_id,
                translator=spec["translator"],
            )
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


def dictionary_spec_value(spec: Dict[str, str], *keys: str) -> Optional[str]:
    for key in keys:
        value = spec.get(key)
        if value is not None and value != "":
            return value
    return None


def build_dictionary_plan(
    editions: List[Dict[str, str]],
    align_src: str,
    align_tgts: List[str],
    original_lang_arg: Optional[str],
    dictionary_specs: Optional[List[str]],
    dictionary_auto: bool,
    dictionary_dir: str,
    default_body_only: bool,
    default_max_items: int,
    default_limit: int,
    default_include_existing: bool,
    skip_missing: bool,
) -> List[Dict[str, Any]]:
    edition_langs = list(dict.fromkeys(e["lang"] for e in editions))
    source_default = (original_lang_arg or align_src).strip().lower()
    plan: List[Dict[str, Any]] = []

    def add_plan_item(
        source: str,
        target: str,
        jsonl_path: Path,
        source_edition_lang: Optional[str],
        body_only: bool,
        max_items: int,
        limit: int,
        include_existing: bool,
        mode: str,
    ) -> None:
        source = source.strip().lower()
        target = target.strip().lower()
        edition_lang = (source_edition_lang or source).strip().lower()
        if source == target:
            raise SystemExit(f"Dictionary source and target cannot match: {source}->{target}")
        if edition_lang not in edition_langs:
            raise SystemExit(
                f"Dictionary source edition language '{edition_lang}' is not present in --edition list: "
                f"{','.join(edition_langs)}"
            )
        plan.append({
            "source": source,
            "target": target,
            "jsonl": jsonl_path,
            "source_edition_lang": edition_lang,
            "body_only": body_only,
            "max_items": max_items,
            "limit": limit,
            "include_existing": include_existing,
            "mode": mode,
        })

    for raw_spec in dictionary_specs or []:
        spec = parse_kv_list(raw_spec)
        source = (dictionary_spec_value(spec, "source", "source-lang", "source_lang") or source_default).strip().lower()
        target_value = dictionary_spec_value(spec, "target", "target-lang", "target_lang")
        targets = [target_value.strip().lower()] if target_value else align_tgts
        if not targets:
            raise SystemExit("Dictionary spec needs a target= value when no alignment targets are available")

        jsonl_path = resolve_dictionary_jsonl(
            dictionary_dir,
            source,
            dictionary_spec_value(spec, "jsonl", "file", "path"),
            required=True,
        )
        source_edition_lang = dictionary_spec_value(spec, "source-edition-lang", "source_edition_lang")
        body_only = parse_bool(dictionary_spec_value(spec, "body-only", "body_only"), default_body_only)
        max_items = parse_int(dictionary_spec_value(spec, "max-items", "max_items"), default_max_items, "max-items")
        limit = parse_int(dictionary_spec_value(spec, "limit"), default_limit, "limit")
        include_existing = parse_bool(
            dictionary_spec_value(spec, "include-existing", "include_existing"),
            default_include_existing,
        )

        for target in targets:
            add_plan_item(
                source,
                target,
                jsonl_path,
                source_edition_lang,
                body_only,
                max_items,
                limit,
                include_existing,
                mode="explicit",
            )

    if dictionary_auto:
        if source_default not in edition_langs:
            log(
                "dictionary_skip",
                level="WARN",
                source=source_default,
                editions=",".join(edition_langs),
                reason="source_edition_not_ingested",
            )
        else:
            for target in align_tgts:
                if target == source_default:
                    continue
                jsonl_path = resolve_dictionary_jsonl(
                    dictionary_dir,
                    source_default,
                    configured_file=None,
                    required=not skip_missing,
                )
                if jsonl_path is None:
                    log(
                        "dictionary_skip",
                        level="WARN",
                        source=source_default,
                        target=target,
                        dictionary_dir=resolve_local_dir(dictionary_dir),
                        reason="dictionary_file_not_found",
                    )
                    continue
                add_plan_item(
                    source_default,
                    target,
                    jsonl_path,
                    source_default,
                    default_body_only,
                    default_max_items,
                    default_limit,
                    default_include_existing,
                    mode="auto",
                )

    deduped: List[Dict[str, Any]] = []
    seen: set[Tuple[str, str, str, str, bool, int, int, bool]] = set()
    for item in plan:
        key = (
            item["source"],
            item["target"],
            item["source_edition_lang"],
            str(item["jsonl"]),
            item["body_only"],
            item["max_items"],
            item["limit"],
            item["include_existing"],
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    return deduped


def run_dictionary_imports(work: str, plan: List[Dict[str, Any]]) -> None:
    if not plan:
        log("dictionary_skip", work=work, reason="no_dictionary_plan")
        return

    importer = SCRIPTS_DIR / "import_dictionary.py"
    for item in plan:
        cmd = [
            sys.executable,
            str(importer),
            "--jsonl",
            str(item["jsonl"]),
            "--source",
            item["source"],
            "--target",
            item["target"],
            "--work",
            work,
            "--source-edition-lang",
            item["source_edition_lang"],
            "--max-items",
            str(item["max_items"]),
            "--limit",
            str(item["limit"]),
        ]
        if item["body_only"]:
            cmd.append("--body-only")
        if not item["include_existing"]:
            cmd.append("--missing-only")

        log(
            "dictionary_start",
            cmd=" ".join(cmd),
            work=work,
            source=item["source"],
            target=item["target"],
            source_edition_lang=item["source_edition_lang"],
            jsonl=item["jsonl"],
            mode=item["mode"],
        )
        res = subprocess.run(cmd, cwd=SCRIPTS_DIR)
        if res.returncode != 0:
            raise RuntimeError(f"Dictionary import failed for {item['source']}->{item['target']}")
        log("dictionary_ok", work=work, source=item["source"], target=item["target"], mode=item["mode"])


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
        help="Bearer token for secured admin ingestion API (default: MLR_ADMIN_API_TOKEN or MLR_ADMIN_TOKEN from .env/env)",
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
    p.add_argument(
        "--generate-edition",
        action="append",
        help=(
            "AI-generated literary translation edition spec like: "
            "lang=en,source=de,translator=\"AI literary translation\""
        ),
    )
    p.add_argument(
        "--generate-max-source-chars",
        type=int,
        default=250000,
        help="Maximum source characters allowed for each generated edition",
    )
    p.add_argument(
        "--generate-chunk-max-chars",
        type=int,
        default=1800,
        help="Maximum characters sent to the literary translation endpoint per request",
    )
    p.add_argument(
        "--generate-context-chars",
        type=int,
        default=600,
        help="Characters of previous generated paragraph included as literary context",
    )
    p.add_argument(
        "--generate-translate-timeout-sec",
        type=int,
        default=120,
        help="Timeout for each admin literary translation request",
    )
    p.add_argument(
        "--generate-dry-run",
        action="store_true",
        help="Estimate generated-translation size and chunks, then stop before calling AI",
    )
    p.add_argument(
        "--dictionary",
        action="append",
        help=(
            "Optional dictionary import spec like: "
            "source=de,target=en,jsonl=/dictionaries/kaikki.org-dictionary-German.jsonl.gz"
        ),
    )
    p.add_argument(
        "--dictionary-auto",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Automatically import missing dictionary entries for source->target alignment pairs when files are available",
    )
    p.add_argument(
        "--dictionary-dir",
        default=default_dictionary_dir(),
        help="Directory containing Kaikki/Wiktextract dictionary JSONL files (default: DICTIONARY_DIR or /dictionaries)",
    )
    p.add_argument(
        "--dictionary-max-items",
        type=int,
        default=4,
        help="Maximum definitions/examples/translations per imported dictionary word",
    )
    p.add_argument(
        "--dictionary-limit",
        type=int,
        default=0,
        help="Stop each dictionary import after this many entries; 0 means no limit",
    )
    p.add_argument(
        "--dictionary-body-only",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Collect dictionary terms only from body text after --body-start when available",
    )
    p.add_argument(
        "--dictionary-include-existing",
        action="store_true",
        help="Re-import terms even if dictionary entries already exist for the source/target pair",
    )
    p.add_argument(
        "--dictionary-skip-missing",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Skip automatic dictionary warmup when the source JSONL file is not mounted",
    )

    # Repeatable editions
    p.add_argument(
        "--edition",
        action="append",
        required=True,
        help='Edition spec like: "lang=de,file=classpath:books/x.epub,translator=Name"',
    )

    args = p.parse_args()

    if args.dictionary_max_items < 1:
        raise SystemExit("--dictionary-max-items must be >= 1")
    if args.dictionary_limit < 0:
        raise SystemExit("--dictionary-limit must be >= 0")
    if args.generate_max_source_chars < 1:
        raise SystemExit("--generate-max-source-chars must be >= 1")
    if args.generate_chunk_max_chars < 1:
        raise SystemExit("--generate-chunk-max-chars must be >= 1")
    if args.generate_context_chars < 0:
        raise SystemExit("--generate-context-chars must be >= 0")
    if args.generate_translate_timeout_sec < 1:
        raise SystemExit("--generate-translate-timeout-sec must be >= 1")

    editions: List[Dict[str, str]] = []
    for ed_str in args.edition:
        ed = parse_kv_list(ed_str)
        if "lang" not in ed or "file" not in ed:
            raise SystemExit("Each --edition must include lang= and file=")
        ed["lang"] = ed["lang"].strip().lower()
        editions.append(ed)

    generated_editions = parse_generated_editions(
        args.generate_edition,
        source_default=(args.align_src or args.original_lang or editions[0]["lang"]).strip().lower(),
        defaults={
            "max_source_chars": args.generate_max_source_chars,
            "chunk_max_chars": args.generate_chunk_max_chars,
            "context_chars": args.generate_context_chars,
            "translate_timeout_sec": args.generate_translate_timeout_sec,
        },
    )
    pipeline_editions: List[Dict[str, str]] = editions + [
        {"lang": spec["lang"], "translator": spec["translator"]}
        for spec in generated_editions
    ]

    if len(pipeline_editions) < 2:
        raise SystemExit("Provide at least two total editions via --edition and/or --generate-edition so we can align.")

    align_plan = build_alignment_plan(
        editions=pipeline_editions,
        align_src_arg=args.align_src,
        align_tgt_arg=args.align_tgt,
        original_lang_arg=args.original_lang,
        aligner=args.aligner,
        method_arg=args.method,
    )
    align_src = align_plan[0][0]
    align_tgts = [t for (_, t, _) in align_plan]
    dictionary_plan = build_dictionary_plan(
        editions=pipeline_editions,
        align_src=align_src,
        align_tgts=align_tgts,
        original_lang_arg=args.original_lang,
        dictionary_specs=args.dictionary,
        dictionary_auto=args.dictionary_auto,
        dictionary_dir=args.dictionary_dir,
        default_body_only=args.dictionary_body_only,
        default_max_items=args.dictionary_max_items,
        default_limit=args.dictionary_limit,
        default_include_existing=args.dictionary_include_existing,
        skip_missing=args.dictionary_skip_missing,
    )

    log("add_book_start",
        work=args.work,
        author=args.author or "n/a",
        description=args.description or "n/a",
        editions=",".join([e["lang"] for e in editions]),
        generated_editions=",".join([f"{e['source']}->{e['lang']}" for e in generated_editions]) or "none",
        api_base_url=args.api_base_url,
        align_src=align_src,
        align_tgts=",".join(align_tgts),
        align_pairs=",".join([f"{s}->{t}" for (s, t, _) in align_plan]),
        methods=",".join([m for (_, _, m) in align_plan]),
        dictionary_pairs=",".join([f"{item['source']}->{item['target']}" for item in dictionary_plan]) or "none",
        dictionary_auto=args.dictionary_auto,
        dictionary_dir=args.dictionary_dir,
        aligner=args.aligner)

    # Ingest each edition
    if args.reset_existing:
        log("reset_start", work=args.work)
        reset_existing(args.work, list(dict.fromkeys([e["lang"] for e in pipeline_editions])))

    for ed in editions:
        lang = ed["lang"]
        file_arg = resolve_file_arg(ed["file"])
        publisher = ed.get("publisher")
        translator = ed.get("translator")

        local_file = Path(file_arg)
        if local_file.exists() and local_file.is_file():
            ingest_upload_via_api(
                api_base_url=args.api_base_url,
                work=args.work,
                year=args.year,
                author=args.author,
                description=args.description,
                lang=lang,
                file_path=local_file,
                publisher=publisher,
                translator=translator,
                original_lang=args.original_lang,
                poll_interval_sec=args.poll_interval_sec,
                timeout_sec=args.ingest_timeout_sec,
                admin_token=args.admin_token,
            )
        else:
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

    run_generated_editions(
        work=args.work,
        year=args.year,
        specs=generated_editions,
        api_base_url=args.api_base_url,
        admin_token=args.admin_token,
        body_only=args.dictionary_body_only,
        dry_run=args.generate_dry_run,
    )
    if args.generate_dry_run and generated_editions:
        log("generate_literary_dry_run_done", work=args.work)
        return

    # Warm dictionary terms before embeddings/alignment; it only needs segmented text.
    run_dictionary_imports(args.work, dictionary_plan)

    # Embed any new sentences (idempotent)
    run_embed()

    # Align source -> each target from plan
    for src, tgt, method in align_plan:
        run_align(args.work, src, tgt, method, args.aligner, args.threshold, args.window)

    # Verify in DB
    verify_work_and_editions(args.work, pipeline_editions)


if __name__ == "__main__":
    main()
