# Parity API Checks

This directory contains a small black-box parity harness for checking that the monolith and microservices stacks return the same API results for the core runtime flows.

- one stack runs at a time
- both use the same `BASE_URL`
- monolith responses are recorded as snapshots
- microservices responses are verified against those snapshots

Current parity scope:
- `GET /api/catalog/books`
- `GET /api/reader/pages` for page `0` and one later page
- `GET /api/reader/front-matter`
- `GET /api/translate/word`
- `POST /api/translate/sentence`
- `POST /api/translate/explain`

## Preconditions

- Both stacks must use the same database contents.
- Both stacks must use `translate.provider=stub`.
- Run only one stack at a time.

## Record baseline snapshots

Run the monolith first and record its responses:

```bash
BASE_URL=http://localhost:8080 node perf/parity/parity.mjs record
```

This creates snapshots in:

```text
perf/parity/snapshots/
```

The recorded fixture includes the chosen book, language pair, page size, and sample sentence, so later verification uses the exact same requests.

Optional fixed fixture selection:

```bash
BASE_URL=http://localhost:8080 \
BOOK_ID=4 \
SRC_LANG=en \
TGT_LANG=de \
PAGE_SIZE=20 \
node perf/parity/parity.mjs record
```

## Verify against snapshots

Stop the monolith, start the microservices stack on the same port, then run:

```bash
BASE_URL=http://localhost:8080 node perf/parity/parity.mjs verify
```

The command exits with:
- `0` if all responses match
- `1` if any case differs from the recorded monolith baseline