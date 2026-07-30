#!/usr/bin/env python3
import argparse
import gzip
import json
import os
import re
import sys
import unicodedata
from pathlib import Path
from typing import Optional

import psycopg


SOURCE_NAME = "Wiktionary via Kaikki/Wiktextract"
SOURCE_URL = "https://kaikki.org/"
GENDER_LABELS = {
    "m": "masculine",
    "masculine": "masculine",
    "f": "feminine",
    "feminine": "feminine",
    "n": "neuter",
    "neuter": "neuter",
    "c": "common",
    "common": "common",
}


def normalize_word(text: str) -> str:
    text = unicodedata.normalize("NFC", text or "").strip().casefold()
    while text and not unicodedata.category(text[0]).startswith("L"):
        text = text[1:]
    while text and not unicodedata.category(text[-1]).startswith("L"):
        text = text[:-1]
    return text


def tokenize(text: str) -> set[str]:
    words: set[str] = set()
    for raw in re.findall(r"[^\W\d_]+(?:['’.-][^\W\d_]+)?", text or "", flags=re.UNICODE):
        normalized = normalize_word(raw)
        if len(normalized) >= 2:
            words.add(normalized)
    return words


def load_book_terms(conn: psycopg.Connection, source_lang: str) -> set[str]:
    terms: set[str] = set()
    with conn.cursor() as cur:
        cur.execute(
            """
            select s.text
            from segment s
            join edition e on e.id = s.edition_id
            where e.lang = %s
              and s.level = 'sentence'
            """,
            (source_lang,),
        )
        for (text,) in cur:
            terms.update(tokenize(text))
    return terms


def as_string_list(values, limit: int) -> list[str]:
    result: list[str] = []
    if not values:
        return result
    for value in values:
        if isinstance(value, str):
            text = value.strip()
        elif isinstance(value, dict):
            text = str(value.get("text") or value.get("word") or value.get("english") or "").strip()
        else:
            text = ""
        if text and text not in result:
            result.append(text)
        if len(result) >= limit:
            break
    return result


def collect_gender_values(*values) -> list[str]:
    found: list[str] = []

    def add(value) -> None:
        if value is None:
            return
        if isinstance(value, str):
            parts = re.split(r"[,;/\s]+", value)
        elif isinstance(value, dict):
            parts = value.values()
        elif isinstance(value, (list, tuple, set)):
            parts = value
        else:
            parts = [value]

        for part in parts:
            if isinstance(part, (dict, list, tuple, set)):
                add(part)
                continue
            token = str(part).strip().lower()
            label = GENDER_LABELS.get(token)
            if label and label not in found:
                found.append(label)

    for value in values:
        add(value)
    return found


def extract_entry(obj: dict, source_lang: str, target_lang: str, max_items: int) -> Optional[dict]:
    word = str(obj.get("word") or "").strip()
    normalized = normalize_word(word)
    if not word or not normalized:
        return None

    lang_code = obj.get("lang_code")
    if lang_code and str(lang_code).lower() != source_lang:
        return None

    definitions: list[str] = []
    examples: list[str] = []
    translations: list[str] = []
    part_of_speech = str(obj.get("pos") or "").strip()
    lemma = str(obj.get("lemma") or "").strip()
    genders = collect_gender_values(
        obj.get("gender"),
        obj.get("genders"),
        obj.get("tags"),
        obj.get("raw_tags"),
    )

    for template in obj.get("head_templates") or []:
        if isinstance(template, dict):
            genders.extend(
                value for value in collect_gender_values(template.get("args"))
                if value not in genders
            )

    for sense in obj.get("senses") or []:
        if not isinstance(sense, dict):
            continue

        for gender in collect_gender_values(
            sense.get("gender"),
            sense.get("genders"),
            sense.get("tags"),
            sense.get("raw_tags"),
        ):
            if gender not in genders:
                genders.append(gender)

        for definition in as_string_list(sense.get("glosses") or sense.get("raw_glosses"), max_items):
            if definition not in definitions:
                definitions.append(definition)
            if len(definitions) >= max_items:
                break

        for example in as_string_list(sense.get("examples"), max_items):
            if example not in examples:
                examples.append(example)
            if len(examples) >= max_items:
                break

        for tr in sense.get("translations") or []:
            if not isinstance(tr, dict):
                continue
            code = str(tr.get("code") or tr.get("lang_code") or "").lower()
            if code and code != target_lang:
                continue
            translated = str(tr.get("word") or "").strip()
            if translated and translated not in translations:
                translations.append(translated)
            if len(translations) >= max_items:
                break

    for tr in obj.get("translations") or []:
        if len(translations) >= max_items:
            break
        if not isinstance(tr, dict):
            continue
        code = str(tr.get("code") or tr.get("lang_code") or "").lower()
        if code and code != target_lang:
            continue
        translated = str(tr.get("word") or "").strip()
        if translated and translated not in translations:
            translations.append(translated)

    if not definitions and not translations and not examples:
        return None

    return {
        "surface": word,
        "normalized": normalized,
        "lemma": lemma,
        "part_of_speech": part_of_speech,
        "gender": ", ".join(genders),
        "definitions": definitions[:max_items],
        "examples": examples[:max_items],
        "translations": translations[:max_items],
    }


def upsert_entry(conn: psycopg.Connection, source_lang: str, target_lang: str, entry: dict) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            insert into dictionary_entry (
              source_lang, target_lang, surface, normalized, lemma, part_of_speech, gender,
              definitions_json, examples_json, translations_json, source_name, source_url
            )
            values (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb, %s, %s)
            on conflict (source_lang, target_lang, normalized) do update set
              surface = excluded.surface,
              lemma = excluded.lemma,
              part_of_speech = excluded.part_of_speech,
              gender = excluded.gender,
              definitions_json = excluded.definitions_json,
              examples_json = excluded.examples_json,
              translations_json = excluded.translations_json,
              source_name = excluded.source_name,
              source_url = excluded.source_url,
              last_accessed_at = now()
            """,
            (
                source_lang,
                target_lang,
                entry["surface"],
                entry["normalized"],
                entry["lemma"],
                entry["part_of_speech"],
                entry["gender"],
                json.dumps(entry["definitions"], ensure_ascii=False),
                json.dumps(entry["examples"], ensure_ascii=False),
                json.dumps(entry["translations"], ensure_ascii=False),
                SOURCE_NAME,
                SOURCE_URL,
            ),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Import local dictionary entries from Kaikki/Wiktextract JSONL.")
    parser.add_argument("--jsonl", required=True, help="Path to a Kaikki/Wiktextract .jsonl file")
    parser.add_argument("--source", required=True, choices=["en", "de", "es"], help="Source language code")
    parser.add_argument("--target", required=True, choices=["en", "de", "es"], help="Target language code")
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL"), help="PostgreSQL connection string")
    parser.add_argument("--all-entries", action="store_true", help="Import all matching entries instead of only words found in books")
    parser.add_argument("--max-items", type=int, default=4, help="Maximum definitions/examples/translations per word")
    parser.add_argument("--limit", type=int, default=0, help="Stop after importing this many entries; 0 means no limit")
    args = parser.parse_args()

    if not args.database_url:
        print("DATABASE_URL is required", file=sys.stderr)
        return 2

    jsonl_path = Path(args.jsonl)
    if not jsonl_path.exists():
        print(f"Dictionary file not found: {jsonl_path}", file=sys.stderr)
        return 2

    with psycopg.connect(args.database_url) as conn:
        allowed_terms: Optional[set[str]] = None
        if not args.all_entries:
            allowed_terms = load_book_terms(conn, args.source)
            print(f"Loaded {len(allowed_terms)} unique {args.source} terms from segmented books")

        imported = 0
        scanned = 0
        opener = gzip.open if jsonl_path.suffix == ".gz" else open
        with opener(jsonl_path, "rt", encoding="utf-8") as fh:
            for line in fh:
                scanned += 1
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue

                entry = extract_entry(obj, args.source, args.target, args.max_items)
                if not entry:
                    continue
                if allowed_terms is not None and entry["normalized"] not in allowed_terms:
                    continue

                upsert_entry(conn, args.source, args.target, entry)
                imported += 1
                if imported % 500 == 0:
                    conn.commit()
                    print(f"Imported {imported} entries after scanning {scanned} lines")
                if args.limit and imported >= args.limit:
                    break

        conn.commit()
        print(f"Done. Imported {imported} entries from {jsonl_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
