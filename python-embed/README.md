# Python Helpers

This directory contains the Python helpers used by the monolith ingestion pipeline:

- `add_book.py`
- `embed_sentences.py`
- `align_sentences.py`
- `align_sentences_dp.py`

`add_book.py` is responsible for ingestion and embedding, it:

1. submits ingestion jobs to the running backend
2. waits for job completion
3. runs embedding and alignment helpers
4. verifies the result in PostgreSQL

## Setup

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

For production-style Docker deployments, the same scripts can be run through
the `ingest-runner` profile in the root `docker-compose.prod.yml`. This avoids
installing the Python/NLP dependencies on the host and reuses a Docker volume
for downloaded model files:

```bash
docker compose -f docker-compose.prod.yml --env-file .env --profile ingest build ingest-runner

docker compose -f docker-compose.prod.yml --env-file .env --profile ingest run --rm ingest-runner \
  --work "Metamorphosis" \
  --year 1915 \
  --author "Franz Kafka" \
  --original-lang de \
  --edition "lang=de,file=classpath:books/metamorphosis_de.epub" \
  --edition "lang=en,file=classpath:books/metamorphosis_en.epub" \
  --aligner dp \
  --reset-existing
```

## Environment

Database access for embedding/alignment:

```bash
export DATABASE_URL="postgresql://mlr:mlr_password@localhost:5432/mlr"
```

Optional API base URL override for ingestion job submission:

```bash
export MLR_API_BASE_URL="http://localhost:8080"
```

## Example

Run from `python-embed/` with the monolith app already running:

```bash
python add_book.py \
  --work "Faust (Part I)" \
  --year 1806 \
  --author "Johann Wolfgang von Goethe" \
  --original-lang de \
  --edition "lang=de,file=classpath:books/faust_de_1.epub" \
  --edition "lang=en,file=classpath:books/faust_en_1.epub" \
  --align-src de \
  --align-tgt en \
  --aligner dp
```
