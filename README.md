# Multilang-reader-mono (a draft)

Monolith MVP for a multilingual reading assistant built to support a future **monolith vs microservices** comparison.

Goal: enable parallel reading (original + publisher translation), word/sentence translation, optional AI explanation, and research-friendly observability (metrics, logs).  
Current focus: **data pipeline** (EPUB ingestion → segmentation → embeddings → alignment) + basic web UI.

---

## Tech stack (current)

**Backend**
- Java 23
- Spring Boot 3.x
- Micrometer + Actuator (Prometheus endpoint)
- JDBC (NamedParameterJdbcTemplate)

**Database**
- PostgreSQL 17
- pgvector

**Offline/batch**
- Python (embeddings + alignment scripts)

**Sentence detecting models:**
- opennlp-de-ud-gsd-sentence-1.3-2.5.4.bin
- opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin
- opennlp-ru-ud-gsd-sentence-1.3-2.5.4.bin
- opennlp-es-ud-gsd-sentence-1.3-2.5.4.bin
- opennlp-fr-ud-gsd-sentence-1.3-2.5.4.bin

Stored in `src/main/resources/models` as `de-sent.bin`, `en-sent.bin`
Used for splitting sentences (in `EpubExtractor`). 
Models are taken from: https://opennlp.apache.org/models.html 


**Frontend**
- React + TypeScript + Vite
- Tailwind
- Two pages: Catalog + Reader

**AI providers**
- Pluggable provider selection:
    - `stub` (default)
    - `openai`
    - `ollama`
    - `google`

---


---

## Running the app (dev)

### 1) DB
Start Postgres (docker or local). Ensure pgvector is installed in your DB.

If using docker-compose:
- Default uses your local Postgres (`host.docker.internal`).
- To run containerized Postgres, enable the `db` profile and point app to it.
- For API keys and DB overrides, use a local `.env` file (see `.env.example`).

Examples:
```
# Use local Postgres (default)
docker compose up --build

# Use container Postgres
DB_HOST=postgres docker compose --profile db up --build

# Use local .env for API keys / DB settings
docker compose --env-file .env up --build
```

### 2) Backend

```./gradlew bootRun```

Set the environmental variables:
```export OPENAI_API_KEY=your_key``` or / and
```export GOOGLE_API_KEY=your_key```

Prometheus metrics:
- http://localhost:8080/actuator/prometheus

### 3) Frontend

```
cd web-ui
npm i
npm run dev
```

---

## Configuration

### Translation provider switch

In `application.yml`:

```yaml
translate:
  provider: stub  # stub | openai | google | ollama

  openai:
    apiKey: ${OPENAI_API_KEY:}
    model: gpt-4o-mini
    timeoutSeconds: 30

  google:
    apiKey: ${GOOGLE_API_KEY:}
    model: gemini-1.5-flash-latest
    baseUrl: https://generativelanguage.googleapis.com
    timeoutSeconds: 30

  ollama:
    baseUrl: http://localhost:11434
    model: llama3.1:8b
```

### OpenAI key

If running from terminal:

```OPENAI_API_KEY="sk-..." ./gradlew bootRun```

If running from IntelliJ:

```Run config → Environment variables → OPENAI_API_KEY=...```

### Ollama

Start local service:

```
ollama serve
ollama pull llama3.1:8b
```

Set translate.provider: ollama

### Database schema (current working model)

```sql
-- Required
CREATE EXTENSION IF NOT EXISTS vector;

-- Works (logical book/work concept)
CREATE TABLE IF NOT EXISTS work (
  id BIGSERIAL PRIMARY KEY,
  title TEXT NOT NULL UNIQUE,
  author TEXT,
  pub_year INT,
  public_domain BOOLEAN DEFAULT TRUE
);

-- Language-specific editions/translations of a work
CREATE TABLE IF NOT EXISTS edition (
  id BIGSERIAL PRIMARY KEY,
  work_id BIGINT NOT NULL REFERENCES work(id) ON DELETE CASCADE,
  lang TEXT NOT NULL,
  publisher TEXT,
  translator TEXT,
  year INTEGER,
  source_uri TEXT,
  -- heuristic marker for body start:
  body_start_segment_id BIGINT,
  UNIQUE(work_id, lang, translator)
);

-- Segment type hierarchy
DO $$ BEGIN
  CREATE TYPE segment_level AS ENUM ('chapter','paragraph','sentence');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Segmented content from EPUB
CREATE TABLE IF NOT EXISTS segment (
  id BIGSERIAL PRIMARY KEY,
  edition_id BIGINT NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
  level segment_level NOT NULL,
  ord INT NOT NULL,
  parent_id BIGINT REFERENCES segment(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  cfi TEXT,
  UNIQUE(edition_id, level, ord, parent_id)
);

-- Sentence embeddings
CREATE TABLE IF NOT EXISTS embedding (
  segment_id BIGINT PRIMARY KEY NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  model TEXT NOT NULL,
  vec vector(384), -- embedding size
  UNIQUE(segment_id, model)
);

-- indexing
CREATE INDEX IF NOT EXISTS embedding_vec_hnsw_cosine
  ON embedding USING hnsw (vec vector_cosine_ops);

-- Sentence alignments between editions
CREATE TABLE IF NOT EXISTS alignment (
  id BIGSERIAL PRIMARY KEY,
  work_id BIGINT REFERENCES work(id) ON DELETE CASCADE,
  edition_src_id BIGINT REFERENCES edition(id) ON DELETE CASCADE,
  edition_tgt_id BIGINT REFERENCES edition(id) ON DELETE CASCADE,
  left_segment_id BIGINT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  right_segment_id BIGINT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
  method TEXT NOT NULL,
  score DOUBLE PRECISION NOT NULL,
  created_at TIMESTAMP DEFAULT now(),
  CONSTRAINT alignment_left_right_unique
    UNIQUE (left_segment_id, right_segment_id, method)
);

```

## Adding books to catalogue 
Example on how to add a book using `add_book.py` script. Call from python-embed/

Note: `add_book.py` now submits ingestion jobs to the running backend (`POST /api/admin/ingest/jobs`), so start the app first (`./gradlew bootRun`).

```
python add_book.py \
    --work "A Christmas Carol" --year 1843 --author "Charles Dickens" --original-lang en \
    --edition "lang=en,file=classpath:books/navidad_en.epub" \
    --edition "lang=es,file=classpath:books/navidad_es.epub" \
    --aligner dp \
    --reset-existing \
    --body-start 'lang=en,text="Marley was dead, to begin with"' \
    --body-start 'lang=es,text="Marley estaba muerto; eso para empezar"'
```

**Embedding & alignment:**
- Embeddings are computed with sentence-transformers (`paraphrase-multilingual-MiniLM-L12-v2`) via `embed_sentences.py` (reads `DATABASE_URL`). The model name is logged when embedding runs.
- Embedding step is idempotent: it only fills missing rows, so existing sentence embeddings are not recomputed.
- Aligners:
  - `dp` (default): dynamic programming, supports 1–1, 1–0, 0–1, 2–1, 1–2. Better coverage, slower (O(N*M)).
  - `greedy`: windowed greedy (default threshold 0.6, window 10), much faster but only 1–1; lowers coverage on long/uneven texts. Lower threshold (e.g., 0.35–0.45) for more matches.
- If you pass 3+ editions, `add_book.py` aligns `source -> each other language` in one run.
  - Source is chosen by `--align-src`, otherwise `--original-lang`.
  - For 3+ editions, specify source explicitly (`--original-lang` or `--align-src`) to avoid ambiguity.
- `--body-start 'lang=xx,text="first sentence fragment"'` lets the script set `body_start_segment_id` by matching the first sentence containing the fragment per language, so front matter is skipped in alignment/paging.
- Catalog language order follows edition insertion order (array_agg ordered by edition id), so pass editions to `add_book.py` in your preferred source→target order (first = source/left, second = target/right).

## Async ingestion API (app keeps running)

You can ingest while the backend is running (no restart/stop required):

1. Submit job:
```bash
curl -X POST http://localhost:8080/api/admin/ingest/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "workTitle":"A Christmas Carol",
    "lang":"en",
    "fileSpec":"classpath:books/navidad_en.epub",
    "year":1843,
    "author":"Charles Dickens",
    "originalLang":"en"
  }'
```

2. Poll status:
```bash
curl http://localhost:8080/api/admin/ingest/jobs/1
```

Statuses: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`.

## Benchmark plan

For monolith vs microservices comparison, use automated API workload rather than manual UI interaction.

Primary workload script:
- `perf/k6/benchmark.ts`

Helper runner for the planned matrix:
- `perf/k6/run-matrix.sh`

General rules:
- run only one stack at a time
- keep the same DB contents for both runs
- keep the same provider mode for both runs
- keep the same `BOOK_ID`, `SRC_LANG`, `TGT_LANG` to get strict reproducibility
- prefer a first comparison pass with `translate.provider=stub`

Recommended baseline matrix:

| Scenario | Mode | VUs | Duration | Purpose |
|---|---|---:|---:|---|
| Catalog | `catalog` | 4 | 2m | metadata-only path |
| Reader | `reader` | 4 | 2m | core reading/alignment path |
| Translate | `translate` | 2 | 2m | provider-heavy path |
| Mixed | `mixed` | 4 | 3m | realistic combined workload |

Recommended process:
1. Start monolith stack only.
2. Run the same matrix against monolith.
3. Save `k6` summaries and Grafana screenshots/notes.
4. Stop monolith stack.
5. Start microservices stack only.
6. Run the exact same matrix against microservices.
7. Repeat each full matrix at least 3 times before drawing conclusions.

Example manual run:

```bash
BASE_URL=http://localhost:8080 \
MODE=mixed \
VUS=4 \
DURATION=3m \
k6 run perf/k6/benchmark.ts
```

Example matrix run:

```bash
chmod +x perf/k6/run-matrix.sh
BASE_URL=http://localhost:8080 \
BOOK_ID=4 \
SRC_LANG=en \
TGT_LANG=de \
./perf/k6/run-matrix.sh monolith
```

Then run the same command against the microservices gateway:

```bash
BASE_URL=http://localhost:8080 \
BOOK_ID=4 \
SRC_LANG=en \
TGT_LANG=de \
./perf/k6/run-matrix.sh microservices
```

What to record for each run:
- architecture (`monolith` or `microservices`)
- provider mode (`stub`, `openai`, `google`, `ollama`)
- scenario / mode
- VUs and duration
- `k6` summary JSON
- Grafana values for:
  - external API p95 latency
  - external API RPS
  - external API 5xx rate
  - reader p95 latency
  - reader fallback rate
  - translate provider p95 latency
  - translate provider error rate
  - CPU
  - JVM heap
  - Hikari active/idle connections

Notes:
- When a real provider is used instead of `stub`, interpret tail latency carefully because provider/network variance can dominate architecture overhead.

### TODO ???

-  Add Loki + Promtail to capture the Python logfmt output.



# copy paste section
python add_book.py \
--work "Trafalgar" --year 1873 --author "Benito Pérez Galdós" \
--edition "lang=es,file=classpath:books/trafalgar_es.epub" \
--edition "lang=en,file=classpath:books/trafalgar_en.epub" \
--aligner dp \
--reset-existing \
--body-start 'lang=es,text="Se me permitirá que antes de referir el gran suceso de que fui testigo"' \
--body-start 'lang=en,text="I trust that, before relating the important events of which"' 


python add_book.py \
--work "The Last Day of a Condemned Man" --year 1829 --original-lang "fr" --author "Victor-Marie Hugo" \
--edition "lang=fr,file=classpath:books/hugo_fr.epub" \
--edition "lang=en,file=classpath:books/hugo_en.epub" \
--edition "lang=ru,file=classpath:books/hugo_ru.epub" \
--aligner dp \
--reset-existing \
--body-start 'lang=fr,text="Condamné à mort"' \
--body-start 'lang=en,text="SENTENCED to death!"'
--body-start 'lang=ru,text="Приговорен к смерти!"'

python align_sentences_dp.py \
--work "The Last Day of a Condemned Man" --src fr --tgt en --method dp-mlMiniLM-v1-fr-en

python align_sentences_dp.py \
--work "The Last Day of a Condemned Man" --src fr --tgt ru --method dp-mlMiniLM-v1-fr-ru



pandoc sandman_en.txt -o sandman_en.epub --metadata title="The Sand-Man"
pandoc sandman_de.txt -o sandman_de.epub --metadata title="Der Sandmann"


pandoc rumpelstilzchen_en.txt \
-o rumpelstilzchen_en.epub \
--metadata title="Rumpelstilzchen" \
--metadata creator="Jacob Grimm and Wilhelm Grimm" \
--metadata language="en" \
--metadata date="1812"


pandoc rumpelstilzchen_de.txt \
-o rumpelstilzchen_de.epub \
--metadata title="Rumpelstilzchen" \
--metadata creator="Jacob Grimm and Wilhelm Grimm" \
--metadata language="de" \
--metadata date="1812"

pandoc rumpelstilzchen_ru.txt \
-o rumpelstilzchen_ru.epub \
--metadata title="Румпельштильцхен" \
--metadata creator="Jacob Grimm and Wilhelm Grimm" \
--metadata language="ru" \
--metadata date="1812"

python python-embed/add_book.py \
--work "Rumpelstilzchen" \
--author "Jacob Grimm and Wilhelm Grimm" \
--year 1812 \
--original-lang de \
--edition "lang=de,file=classpath:books/rumpelstilzchen_de.epub" \
--edition "lang=en,file=classpath:books/rumpelstilzchen_en.epub" \
--edition "lang=ru,file=classpath:books/rumpelstilzchen_ru.epub" \
--aligner dp \
--reset-existing