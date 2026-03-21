# k6 Workloads

This directory contains a small TypeScript `k6` script for comparing the monolith and the microservices gateway under the same API workload.

The script targets:

- `GET /api/catalog/books`
- `GET /api/reader/pages`
- `GET /api/translate/word`
- `POST /api/translate/sentence`
- `POST /api/translate/explain`

It auto-discovers a valid multilingual book from the catalog unless the dataset is overriden through environment variables.

The script is simple:

- `setup()` discovers one valid multilingual book and language pair
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

## Useful environment variables

- `BASE_URL`
- `MODE=catalog|reader|translate|mixed` default `mixed`
- `BOOK_ID`
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
4. The same `k6` command against each target.

Examples:

```bash
BASE_URL=http://localhost:8080 MODE=mixed VUS=4 DURATION=3m k6 run perf/k6/benchmark.ts
```

When comparing architectures, keep the `k6` profile and dataset identical and read results from Prometheus/Grafana rather than from `k6` latency alone.
