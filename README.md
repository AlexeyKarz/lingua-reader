# Multilang-reader-mono

### The deployed project can be found at https://reader.alexeykarz.com.

**This project originated as a University of Nicosia Final Year Project comparing [monolithic](https://github.com/AlexeyKarz/Multilang-Reader) and [microservices](https://github.com/AlexeyKarz/multilang-reader-ms)
architectures. The current repository contains the maintained monolith-based showcase version.**

Monolith implementation of the multilingual reading assistant used in the **monolith vs microservices** comparison.

Goal: enable parallel reading (original + publisher translation), word/sentence translation, optional AI explanation, and a small secure deployment suitable for a public CV/demo project.

Saved vocabulary and review are implemented client-side. A reader can save a
word or sentence after lookup and review it later from the Vocabulary page. The
data stays in the browser's localStorage, so no account is required for the
public demo.

Security note:
- public runtime endpoints are catalog, reader, and translation/explanation
- admin ingestion endpoints are disabled by default
- if admin ingestion is enabled, `/api/admin/**` requires a bearer token
- Prometheus/Grafana are off by default and should not be exposed publicly
- real provider credentials must be supplied only through local/server environment variables

---

## Tech stack

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

**Sentence detection models**
- `src/main/resources/models/de-sent.bin`
- `src/main/resources/models/en-sent.bin`
- `src/main/resources/models/ru-sent.bin`
- `src/main/resources/models/es-sent.bin`
- `src/main/resources/models/fr-sent.bin`

These OpenNLP sentence models are used by `EpubExtractor` during ingestion to split paragraph text into sentence segments.

They are committed to the repository on purpose so ingestion stays reproducible across environments and benchmark runs, without requiring an extra model download step.

Model source:
- Apache OpenNLP models: https://opennlp.apache.org/models.html

Upstream model names currently used:
- `opennlp-de-ud-gsd-sentence-1.3-2.5.4.bin`
- `opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin`
- `opennlp-ru-ud-gsd-sentence-1.3-2.5.4.bin`
- `opennlp-es-ud-gsd-sentence-1.3-2.5.4.bin`
- `opennlp-fr-ud-gsd-sentence-1.3-2.5.4.bin`


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

## Running the app (dev)

### 1) DB
Start Postgres (docker or local). Ensure `pgvector` is installed in your DB.

If using docker-compose:
- Default app config targets a DB on `host.docker.internal`.
- The optional compose DB profile uses `pgvector/pgvector:pg17`.
- To run containerized Postgres, enable the `db` profile and point the app to `postgres`.
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

Prometheus and Grafana are opt-in for local development:

```bash
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus \
docker compose --profile observability up --build
```

When enabled through the local compose file, Grafana and Prometheus bind to `127.0.0.1` only:

- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090

### 2) Backend

```bash
./gradlew bootRun
```

Set environment variables as needed:

```bash
export TRANSLATE_PROVIDER=stub
export OPENAI_API_KEY=your_key
export GOOGLE_API_KEY=your_key
```

Prometheus metrics are disabled by default. To expose them locally:

```bash
export MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus
```

Then:

- http://localhost:8080/actuator/prometheus

### 3) Frontend

```
cd web-ui
npm i
npm run dev
```

## Testing

Backend tests require PostgreSQL with pgvector reachable through the configured
Spring datasource, matching the GitHub Actions Postgres service.

```bash
./gradlew test
```

Frontend verification is split into linting, unit/component tests, production
build, and browser smoke tests:

```bash
cd web-ui
npm ci
npm run lint
npm run test:unit
npm run build
npx playwright install chromium
npm run test:e2e
```

The Playwright smoke tests start the Vite preview server and mock API responses
in the browser. CI installs Chromium with system dependencies before running
them.

## Production-style Docker deployment

Use `docker-compose.prod.yml` for a small public/demo deployment. It runs:

- PostgreSQL + pgvector with a persistent Docker volume
- the Spring Boot monolith on the internal Docker network
- the built React frontend served by nginx
- nginx proxying `/api/**` to the backend

Only the frontend/proxy port is published. The backend and database are not published to the host.

1. Create a server-local `.env` file:

```bash
cp .env.example .env
```

Set at least:

```env
POSTGRES_PASSWORD=<long-random-password>
WEB_PORT=8080
TRANSLATE_PROVIDER=stub
MLR_ADMIN_ENABLED=false
```

2. Start the deployment:

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

On first start, the application creates the required database schema if the
PostgreSQL volume is empty. The catalog will still be empty until demo data is
restored from a prepared dump or ingested through the secured admin workflow.

3. Open:

```text
http://<server>:8080
```

For a real public deployment, put this behind HTTPS using a host-level reverse proxy such as Caddy, Nginx, or your existing server proxy.

### GitHub Actions deployment

The repository includes `.github/workflows/deploy.yml`. It runs backend tests,
frontend linting, frontend unit tests, a production frontend build, and
Playwright browser smoke tests. If Playwright fails, the workflow uploads the
browser report and test-results artifacts for inspection. Deployment then
connects to the Hetzner server over SSH, runs `scripts/deploy-prod.sh`, and
checks the public deployment URL after the server-side healthcheck passes.
Verification runs on pull requests targeting `main` or `prod`. Deployment runs
only on pushes to `main` or `prod`, or when started manually from the GitHub
Actions tab.

The public post-deploy check defaults to:

```text
https://reader.alexeykarz.com/api/catalog/books
```

To use another domain or path, set the GitHub repository/environment variable
`PUBLIC_HEALTHCHECK_URL`.

`.github/workflows/security-audit.yml` tracks dependency security separately
from deployment. It runs npm production audits on pull requests, on a weekly
schedule, and on manual dispatch. Pull requests also run GitHub dependency
review and fail when new high-severity dependency changes are introduced.

`.github/dependabot.yml` enables weekly update pull requests for GitHub
Actions, Gradle, npm, and Docker base images.

The server keeps its own `.env` file. Do not store database passwords, OpenAI
keys, or admin tokens in the repository.

Required GitHub repository secrets:

| Secret | Purpose |
|---|---|
| `DEPLOY_HOST` | Server IP or hostname. |
| `DEPLOY_USER` | SSH user on the server. |
| `DEPLOY_SSH_KEY` | Private SSH key allowed to access the server. |
| `DEPLOY_PATH` | Absolute path to the checked-out repo on the server, for example `/root/multilang-reader`. |
| `DEPLOY_PORT` | Optional SSH port; defaults to `22`. |
| `DEPLOY_KNOWN_HOSTS` | Optional pinned SSH known-hosts line. If omitted, the workflow uses `ssh-keyscan`. |
| `DEPLOY_HEALTHCHECK_URL` | Optional healthcheck URL; defaults to `http://127.0.0.1:8081/api/catalog/books`. |

The first server checkout should be prepared manually:

```bash
git clone <repo-url> /root/multilang-reader
cd /root/multilang-reader
cp .env.example .env
nano .env
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

After that, pushes to `main` or `prod` can deploy automatically.

### Admin ingestion in production

Keep admin ingestion disabled for normal public demo use:

```env
MLR_ADMIN_ENABLED=false
```

To ingest new books temporarily, enable it and set a long random token:

```env
MLR_ADMIN_ENABLED=true
MLR_ADMIN_TOKEN=<long-random-token>
```

Restart the app after changing `.env`:

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d app
```

Admin requests must include:

```http
Authorization: Bearer <long-random-token>
```

The deployed web UI also has a direct admin ingestion page at
`/admin/ingest`. It is not linked from the public navigation. The page stores
the admin token in the current browser tab session and submits uploaded EPUB or
TXT files to the secured admin API. TXT uploads are converted to minimal EPUBs
server-side before the existing ingestion pipeline runs.

Disable admin again after ingestion unless you actively need it.

### Server-side ingestion runner

For complete demo books, prefer the Compose `ingest-runner` profile instead of
calling the ingestion API manually with `curl`. The API ingests EPUB editions,
but the runner also performs the remaining pipeline steps: body-start selection,
sentence embeddings, alignment, and verification.

The runner uses the same internal Docker network as the app and database. Its
Python dependencies are built into a reusable image, and model files are cached
in the `ingest_model_cache` Docker volume so they do not need to be downloaded
again for every book.

1. Temporarily enable admin ingestion in `.env`:

```env
MLR_ADMIN_ENABLED=true
MLR_ADMIN_TOKEN=<long-random-token>
```

2. Recreate the app so the admin settings are applied:

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --force-recreate app
```

3. Build the ingestion runner image:

```bash
docker compose -f docker-compose.prod.yml --env-file .env --profile ingest build ingest-runner
```

4. Run ingestion for a committed EPUB pair:

```bash
docker compose -f docker-compose.prod.yml --env-file .env --profile ingest run --rm ingest-runner \
  --work "Metamorphosis" \
  --year 1915 \
  --author "Franz Kafka" \
  --original-lang de \
  --description "It tells the story of Gregor Samsa, a traveling salesman who wakes one morning to find himself inexplicably transformed into a monstrous insect." \
  --edition "lang=de,file=classpath:books/metamorphosis_de.epub" \
  --edition "lang=en,file=classpath:books/metamorphosis_en.epub" \
  --aligner dp \
  --reset-existing \
  --body-start 'lang=de,text="Gregor Samsa eines Morgens"' \
  --body-start 'lang=en,text="One morning, when Gregor Samsa"'
```

The `file=classpath:books/...` values refer to EPUB files packaged in the app
image from `src/main/resources/books`. If new EPUB files are added, rebuild the
app image before running ingestion:

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build app
```

5. Disable admin ingestion again:

```env
MLR_ADMIN_ENABLED=false
```

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --force-recreate app
```

---

## Configuration

### Security-related settings

| Variable | Default | Purpose |
|---|---|---|
| `MLR_ADMIN_ENABLED` | `false` | Enables `/api/admin/**` endpoints when set to `true`. |
| `MLR_ADMIN_TOKEN` | empty | Required bearer token for `/api/admin/**` when admin is enabled. |
| `MLR_INGESTION_UPLOAD_DIR` | JVM temp dir | Server-side directory for source files uploaded through `/admin/ingest`. |
| `MLR_INGESTION_MAX_FILE_SIZE` | `50MB` | Maximum uploaded EPUB or TXT size. |
| `MLR_INGESTION_MAX_REQUEST_SIZE` | `60MB` | Maximum multipart request size for admin ingestion uploads. |
| `TRANSLATE_PROVIDER` | `stub` | Provider mode: `stub`, `openai`, `google`, or `ollama`. |
| `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI model used when `TRANSLATE_PROVIDER=openai`. |
| `OPENAI_MAX_COMPLETION_TOKENS` | `300` | Output-token cap for each OpenAI response. |
| `TRANSLATE_LIMITS_ENABLED` | `true` | Enables daily quota protection for non-stub providers. |
| `TRANSLATE_LIMITS_PER_IP_DAILY` | `20` | Maximum paid-provider calls per client IP per day. |
| `TRANSLATE_LIMITS_GLOBAL_DAILY` | `200` | Maximum paid-provider calls for the whole public app per day. |
| `TRANSLATE_LIMITS_MAX_TEXT_CHARS` | `800` | Maximum selected text length for paid-provider calls. |
| `TZ` | `UTC` | Container timezone used for daily quota reset at midnight. |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info` | Actuator endpoints exposed over HTTP. Use `health,info,prometheus` only for private/local observability. |

### Translation provider switch

Set `TRANSLATE_PROVIDER` to choose the runtime provider:

```bash
export TRANSLATE_PROVIDER=stub # stub | openai | google | ollama
```

Provider details are configured in `application.yml`:

```yaml
translate:
  provider: ${TRANSLATE_PROVIDER:stub}  # stub | openai | google | ollama

  openai:
    apiKey: ${OPENAI_API_KEY:}
    model: ${OPENAI_MODEL:gpt-4o-mini}
    timeoutSeconds: ${OPENAI_TIMEOUT_SECONDS:30}
    maxCompletionTokens: ${OPENAI_MAX_COMPLETION_TOKENS:300}

  limits:
    enabled: ${TRANSLATE_LIMITS_ENABLED:true}
    perIpDaily: ${TRANSLATE_LIMITS_PER_IP_DAILY:20}
    globalDaily: ${TRANSLATE_LIMITS_GLOBAL_DAILY:200}
    maxTextChars: ${TRANSLATE_LIMITS_MAX_TEXT_CHARS:800}

  google:
    apiKey: ${GOOGLE_API_KEY:}
    model: gemini-2.5-flash
    baseUrl: https://generativelanguage.googleapis.com
    timeoutSeconds: 30

  ollama:
    baseUrl: http://localhost:11434
    model: llama3.1:8b
```

### OpenAI key

For public deployments, use a separate OpenAI project/API key with a small
platform budget. The app also enforces in-process daily limits before calling
OpenAI. If the per-IP or global daily limit is reached, the request is answered
with a visible quota-reset message and the local stub output instead of calling
the paid provider.

If running from terminal:

```OPENAI_API_KEY="sk-..." ./gradlew bootRun```

If running from IntelliJ:

```Run config → Environment variables → OPENAI_API_KEY=...```

For a local quota test:

```bash
TRANSLATE_PROVIDER=openai \
OPENAI_API_KEY="sk-..." \
TRANSLATE_LIMITS_PER_IP_DAILY=1 \
TRANSLATE_LIMITS_GLOBAL_DAILY=2 \
./gradlew bootRun
```

Then call translation twice from the UI. The second call from the same IP should
show the quota reset message and stub output.

When the app is behind a host-level nginx proxy, configure that proxy to
overwrite forwarded IP headers, not append user-supplied ones:

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
```

### Local dictionary and translation cache

Word lookup is dictionary-first. When `/api/translate/word` receives a selected
word, the backend checks the local `dictionary_entry` table before using the
configured translation provider. If dictionary entries are found, the response
contains all matching imported entries, including definitions, examples, part of
speech, gender, translations, source metadata, and form-of/base-form links. If no
entry is found, the app falls back to the provider path.

Literal word and sentence provider responses are stored in `translation_cache`.
Explanation responses are intentionally not cached because they depend more on
context and prompt wording.

The dictionary MVP supports English, German, and Spanish imports. More languages
can be added later by extending the importer language choices and importing the
corresponding dictionary data.

Recommended data source: Kaikki/Wiktextract JSONL exports from Wiktionary:

```text
https://kaikki.org/dictionary/rawdata.html
```

The dictionary data files are not committed to this repository. Download them
outside Git, then import only entries that occur in your ingested books:

```bash
cd python-embed
export DATABASE_URL="postgresql://mlr:mlr_password@localhost:5432/mlr"

python import_dictionary.py \
  --jsonl ~/Downloads/kaikki.org-dictionary-English.jsonl \
  --source en \
  --target es

python import_dictionary.py \
  --jsonl ~/Downloads/kaikki.org-dictionary-German.jsonl \
  --source de \
  --target en

python import_dictionary.py \
  --jsonl ~/Downloads/kaikki.org-dictionary-Spanish.jsonl \
  --source es \
  --target en
```

By default, the importer scans the current `segment` table and imports only
matching book words. Use `--all-entries` only for local experiments, because full
dictionary imports can be large.

If dictionary rows were imported before a dictionary schema change, re-run the
import for the same language pair. Existing rows are updated in place. The
importer now keeps multiple entries for the same written word, so homographs and
different parts of speech can be displayed together. It also performs a second
pass for referenced base forms. For example, if a selected German word is only
found as an inflected form, the importer also attempts to import the base lemma
referenced by Wiktionary/Kaikki.

The reader popover groups dictionary results into main meanings, base-form
meanings, and grammatical forms. This keeps useful lexical translations visible
first while still preserving grammatical-form information from Wiktionary.

To test a lookup after import:

```bash
curl "http://localhost:8080/api/translate/word?text=morning&source=en&target=es"
```

In the reader UI, select a single word. Dictionary hits are shown as a compact
entry with translations, definitions, examples, and a source label. Misses still
use the configured provider or the stub provider.

### Ollama

Start local service:

```
ollama serve
ollama pull llama3.1:8b
```

Set:

```bash
export TRANSLATE_PROVIDER=ollama
```

### Database schema (current working model)

```sql
-- Required
CREATE EXTENSION IF NOT EXISTS vector;

-- Works (logical book/work concept)
CREATE TABLE IF NOT EXISTS work (
  id BIGSERIAL PRIMARY KEY,
  title TEXT NOT NULL UNIQUE,
  author TEXT,
  description TEXT,
  pub_year INT,
  original_lang TEXT,
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

-- Local dictionary entries used before provider fallback on word lookup
CREATE TABLE IF NOT EXISTS dictionary_entry (
  id BIGSERIAL PRIMARY KEY,
  entry_key TEXT NOT NULL UNIQUE,
  source_lang TEXT NOT NULL,
  target_lang TEXT NOT NULL,
  surface TEXT NOT NULL,
  normalized TEXT NOT NULL,
  lemma TEXT,
  part_of_speech TEXT,
  gender TEXT,
  base_surface TEXT,
  base_normalized TEXT,
  form_tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  definitions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  examples_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  translations_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  source_name TEXT,
  source_url TEXT,
  created_at TIMESTAMP DEFAULT now(),
  last_accessed_at TIMESTAMP DEFAULT now(),
  hit_count INT NOT NULL DEFAULT 0
);

-- Persistent cache for literal word/sentence provider calls
CREATE TABLE IF NOT EXISTS translation_cache (
  id BIGSERIAL PRIMARY KEY,
  cache_key TEXT NOT NULL UNIQUE,
  operation TEXT NOT NULL,
  source_lang TEXT NOT NULL,
  target_lang TEXT NOT NULL,
  normalized_text TEXT NOT NULL,
  provider TEXT NOT NULL,
  response_text TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT now(),
  last_accessed_at TIMESTAMP DEFAULT now(),
  hit_count INT NOT NULL DEFAULT 0
);

```

## Adding books to the catalogue 
Example on how to add a book using `add_book.py` script. Call from python-embed/

Note: `add_book.py` submits ingestion jobs to the running backend (`POST /api/admin/ingest/jobs`), so start the app first (`./gradlew bootRun` or `docker compose up`).

```
python add_book.py \
    --work "A Christmas Carol" --year 1843 --author "Charles Dickens" --original-lang en \
    --description "A Christmas ghost story about Ebenezer Scrooge, redemption, and social conscience." \
    --edition "lang=en,file=classpath:books/navidad_en.epub" \
    --edition "lang=es,file=classpath:books/navidad_es.epub" \
    --aligner dp \
    --reset-existing \
    --body-start 'lang=en,text="Marley was dead, to begin with"' \
    --body-start 'lang=es,text="Marley estaba muerto; eso para empezar"'
```

If the `work` table already exists in your local database, add the new optional catalog field once:

```sql
ALTER TABLE work ADD COLUMN IF NOT EXISTS description TEXT;
```

Existing books can then be backfilled manually, for example:

```sql
UPDATE work
SET description = 'A dramatic poem about ambition, knowledge, temptation, and redemption.'
WHERE title = 'Faust (Part I)';
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

You can ingest while the backend is running (no restart/stop required), but the admin API is disabled by default.

Enable it only for trusted local/private administration:

```bash
export MLR_ADMIN_ENABLED=true
export MLR_ADMIN_TOKEN=dev-admin-token
```

Then start/restart the backend.

1. Submit job:
```bash
curl -X POST http://localhost:8080/api/admin/ingest/jobs \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-admin-token" \
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
curl -H "Authorization: Bearer dev-admin-token" \
  http://localhost:8080/api/admin/ingest/jobs/1
```

Statuses: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`.

`python-embed/add_book.py` also supports secured admin ingestion. It reads `MLR_ADMIN_TOKEN` from `.env`/environment or accepts `--admin-token`.

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
- keep the same fixed book set for both runs
- prefer a first comparison pass with `translate.provider=stub`

Recommended book-selection strategy:
- use a small fixed set of 2-4 books, not random selection
- either pin them explicitly with `BOOK_SPECS="4:en:de;7:de:en;9:fr:en"`
- or let the script use the first `BOOK_COUNT` multilingual books from the catalog
- use the same set in both architectures and across repetitions

Recommended baseline matrix:

| Scenario | Mode | VUs | Duration | Purpose |
|---|---|---:|---:|---|
| Catalog | `catalog` | 4 | 2m | metadata-only path |
| Reader | `reader` | 4 | 2m | core reading/alignment path |
| Translate | `translate` | 2 | 2m | provider-heavy path |
| Mixed | `mixed` | 4 | 3m | realistic combined workload |

Recommended process:
1. Start monolith stack only.
2. Run one short warmup manually and do not record it.
3. Run the measured matrix against monolith with a run label such as `mono_r1`.
4. Save `k6` summaries and Grafana screenshots/notes.
5. Stop monolith stack.
6. Start microservices stack only.
7. Run one short warmup manually and do not record it.
8. Run the exact same measured matrix against microservices with a label such as `micro_r1`.
9. Repeat each full measured matrix at least 3 times before drawing conclusions.

Example manual run:

```bash
BASE_URL=http://localhost:8080 \
MODE=mixed \
VUS=4 \
DURATION=3m \
k6 run perf/k6/benchmark.ts
```

Example fixed multi-book run:

```bash
BASE_URL=http://localhost:8080 \
BOOK_SPECS="4:en:de;7:de:en;9:fr:en" \
MODE=mixed \
VUS=4 \
DURATION=3m \
k6 run perf/k6/benchmark.ts
```

Example warmup run:

```bash
BASE_URL=http://localhost:8080 \
BOOK_SPECS="4:en:de;7:de:en;9:fr:en" \
MODE=mixed \
VUS=2 \
DURATION=1m \
k6 run perf/k6/benchmark.ts
```

## Benchmark deployment budget

For the final comparison on Google Cloud, the intended target is:

- **VM type:** `e2-standard-2`
- **Machine budget:** `2 vCPU`, `8 GB RAM`
- **one stack at a time only** (never run monolith and microservices together)

Important scope rules:

- the **frontend is shared** and runs outside the benchmark target budget
- the **database is external/host Postgres** and is not counted inside the app budget
- Prometheus and Grafana run on the same VM, but with fixed sidecar limits in both architectures

### Budget used for comparison

| Budget slice | CPU | Memory | Notes |
|---|---:|---:|---|
| Backend under test | `1.25` | `3584m` | Must be identical between monolith and microservices |
| Prometheus | `0.20` | `256m` | Same in both stacks |
| Grafana | `0.15` | `256m` | Same in both stacks |
| Remaining VM headroom | `0.40` | ~`4 GB` | OS, Docker, filesystem cache, background overhead |

### Monolith container budget

| Container | CPU | Memory |
|---|---:|---:|
| `app` | `1.25` | `3584m` |
| `prometheus` | `0.20` | `256m` |
| `grafana` | `0.15` | `256m` |

If using the optional compose Postgres profile locally:

| Container | CPU | Memory |
|---|---:|---:|
| `postgres` | `0.50` | `1024m` |

That DB profile is for convenience only. For fair monolith vs microservices runs, keep DB mode the same in both architectures.

### JVM runtime policy

The monolith app container uses:

```text
-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError
```

This keeps heap sizing tied to the container memory limit instead of the host machine memory and makes the runtime behavior comparable to the Java services in the microservices stack.

### Why this budget

The goal is not to maximize raw throughput. The goal is to compare the two architectures under the **same constrained backend budget** on a modest VM shape. That makes the experiment reproducible and keeps the architecture overhead visible instead of hiding it behind excess headroom.

The initial backend memory budget was lower, but pilot runs showed that the microservices deployment could not complete benchmark matrices reliably because of OOM-killed services. The final comparison therefore used a shared backend memory budget of **`3584m`** for both architectures. Earlier lower-budget runs are treated as pilot runs rather than as final comparison data.

## Final benchmark snapshot

The final thesis benchmark campaign used:

- shared VM: `e2-standard-2`
- shared backend budget: `1.25 CPU`, `3584m`
- shared provider mode: `stub`
- shared fixture set: `4:en:de;7:fr:ru;9:en:es`
- one warmup and three measured matrix runs per architecture

Aggregated scenario medians from the final campaign:

| Scenario | Monolith p50 | Monolith p95 | Microservices p50 | Microservices p95 |
|---|---:|---:|---:|---:|
| Catalog | `100.59 ms` | `201.75 ms` | `100.82 ms` | `207.88 ms` |
| Reader | `22.79 ms` | `40.50 ms` | `34.29 ms` | `167.09 ms` |
| Translate | `2.86 ms` | `5.68 ms` | `7.98 ms` | `21.35 ms` |
| Mixed | `21.23 ms` | `137.12 ms` | `29.03 ms` | `174.20 ms` |

These numbers are summarized from:

- `perf/k6/results/comparison.csv`
- `perf/k6/results/analysis/summary_by_mode.csv`
- `perf/k6/results/analysis/http_req_p95_stub.png`
- `perf/k6/results/analysis/http_req_median_stub.png`

Interpretation in one sentence:
- under the final stable deployment budget, the monolith and microservices variants were broadly comparable in the lightweight catalog path, while the microservices deployment showed a clear tail-latency penalty in reader and translate and a moderate overall penalty in mixed workload

Example matrix run:

```bash
chmod +x perf/k6/run-matrix.sh
BASE_URL=http://localhost:8080 \
BOOK_SPECS="4:en:de;7:de:en;9:fr:en" \
./perf/k6/run-matrix.sh monolith
```

Then run the same command against the microservices gateway:

```bash
BASE_URL=http://localhost:8080 \
BOOK_SPECS="4:en:de;7:de:en;9:fr:en" \
./perf/k6/run-matrix.sh microservices
```

What to record for each run:
- architecture (`monolith` or `microservices`)
- provider mode (`stub`, `openai`, `google`, `ollama`)
- scenario / mode
- VUs and duration
- `k6` summary JSON
- `k6` CSV row from `perf/k6/results/<architecture>/results.csv`
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
- To merge the two architecture CSVs into one `k6` comparison file and skip labels containing `warmup`, run `node perf/k6/merge-results.mjs`.

## Parity API checks

Before the final benchmark runs, use the parity harness in [perf/parity/README.md](perf/parity/README.md) to confirm that monolith and microservices return equivalent results for the main runtime API flows.

Workflow:
- start monolith with `translate.provider=stub`
- record snapshots:
  - `BASE_URL=http://localhost:8080 node perf/parity/parity.mjs record`
- stop monolith and start microservices on the same port
- verify against the recorded baseline:
  - `BASE_URL=http://localhost:8080 node perf/parity/parity.mjs verify`

Current parity scope:
- `catalog`
- `reader/pages`
- `reader/front-matter`
- `translate/word`
- `translate/sentence`
- `translate/explain`

The parity harness is intentionally black-box and lightweight. It exists to show that both backend architectures expose the same observable API behavior before latency and resource results are interpreted.

## Deployment note

For the first private cloud deployment workflow, see:

- [ops/deployment/README.md](ops/deployment/README.md)

Supporting benchmark docs:

- [perf/k6/README.md](perf/k6/README.md)
- [perf/parity/README.md](perf/parity/README.md)
