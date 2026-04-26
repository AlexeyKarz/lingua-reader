# k6 Workloads

This directory contains a TypeScript `k6` script for comparing the monolith and the microservices gateway under the same API workload.

The script targets:

- `GET /api/catalog/books`
- `GET /api/reader/pages`
- `GET /api/translate/word`
- `POST /api/translate/sentence`
- `POST /api/translate/explain`

It uses a deterministic fixed book set unless overriden through environment variables.

The script contains:

- `setup()` discovers a fixed set of multilingual books and language pairs
- `default()` runs one action per iteration
- `MODE` controls which action is used

## Run

From the repository root:

```bash
BASE_URL=http://localhost:8080 k6 run perf/k6/benchmark.ts
```

Run only one type of request:

```bash
BASE_URL=http://localhost:8080 MODE=reader k6 run perf/k6/benchmark.ts
```

With an explicit book/pair:

```bash
BASE_URL=http://localhost:8080 \
BOOK_ID=4 \
SRC_LANG=en \
TGT_LANG=es \
k6 run perf/k6/benchmark.ts
```

With an explicit fixed multi-book set:

```bash
BASE_URL=http://localhost:8080 \
BOOK_SPECS="4:en:de;7:de:en;9:fr:en" \
k6 run perf/k6/benchmark.ts
```

## Useful environment variables

- `BASE_URL`
- `MODE=catalog|reader|translate|mixed` default `mixed`
- `BOOK_ID`
- `BOOK_SPECS` semicolon-separated `bookId:src:tgt` entries
- `BOOK_COUNT` default `3` when auto-discovering a fixed set
- `SRC_LANG`
- `TGT_LANG`
- `VUS` default `4`
- `DURATION` default `2m`
- `PAGE_SIZE` default `20`
- `MAX_PAGES` default `10`
- `THINK_TIME_SEC` default `1`
- `K6_SUMMARY_PATH`

## Comparison workflow

1. One stack at a time.
2. The same DB contents for both monolith and microservices.
3. Provider mode fixed for a run set.
4. Prefer a fixed benchmark set, not random book selection.
5. The same `k6` command against each target.

Recommended fixed-book strategy:

- use the same 2–4 books for every compared run
- either pin them explicitly with `BOOK_SPECS`
- or let the script use the first `BOOK_COUNT` multilingual books from the catalog

Warmup guidance:

- do not treat the first cold run as the main result
- use a short warmup run, then measure steady-state runs
- repeat each measured run at least 3 times

Minimal run sequence:

1. warm up manually, do not record it:
```bash
BASE_URL=http://localhost:8080 BOOK_SPECS="4:en:de;7:fr:ru;9:en:es" MODE=mixed VUS=2 DURATION=1m k6 run perf/k6/benchmark.ts
```
2. run the measured matrix with labels such as `mono_r1` or `micro_r1`
```bash
BASE_URL=http://localhost:8080 \
  BOOK_SPECS="4:en:de;7:fr:ru;9:en:es" \
  RUN_LABEL=mono_r1 \
  ./perf/k6/run-matrix.sh monolith
```
3. repeat at least 3 times per architecture

## CSV results capture

The helper runner appends one CSV row per scenario after each `k6` run.

Output:

- JSON summaries: `perf/k6/results/<architecture>/*.json`
- CSV table: `perf/k6/results/<architecture>/results.csv`

The CSV contains:

- run metadata: architecture, provider, mode, VUs, duration, run label
- fixed book set used for the run
- key `k6` summary metrics such as request count, failure rate, and median/p95 latency

This CSV is limited to `k6` metrics. Grafana / Prometheus values should be recorded separately for the final tables.

To merge monolith and microservices `k6` rows into one comparison file and skip any row whose `RUN_LABEL` contains `warmup`:

```bash
node perf/k6/merge-results.mjs
```

Examples:

```bash
BASE_URL=http://localhost:8080 MODE=mixed VUS=4 DURATION=3m k6 run perf/k6/benchmark.ts
```
