#!/usr/bin/env bash

set -euo pipefail

ARCHITECTURE="${1:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
BOOK_ID="${BOOK_ID:-}"
BOOK_SPECS="${BOOK_SPECS:-}"
BOOK_COUNT="${BOOK_COUNT:-3}"
SRC_LANG="${SRC_LANG:-}"
TGT_LANG="${TGT_LANG:-}"
THINK_TIME_SEC="${THINK_TIME_SEC:-1}"
PAGE_SIZE="${PAGE_SIZE:-20}"
MAX_PAGES="${MAX_PAGES:-10}"
PROVIDER_MODE="${PROVIDER_MODE:-stub}"
RUN_LABEL="${RUN_LABEL:-$(date -u +%Y%m%dT%H%M%SZ)}"

if [[ -z "$ARCHITECTURE" ]]; then
  echo "Usage: $0 <monolith|microservices>"
  exit 1
fi

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is not installed or not in PATH"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/perf/k6/benchmark.ts"
APPEND_RESULTS_SCRIPT="$ROOT_DIR/perf/k6/append-results.mjs"
RESULT_DIR="$ROOT_DIR/perf/k6/results/$ARCHITECTURE"
CSV_PATH="${CSV_PATH:-$RESULT_DIR/results.csv}"

mkdir -p "$RESULT_DIR"

run_case() {
  local mode="$1"
  local vus="$2"
  local duration="$3"
  local name="$4"
  local out="$RESULT_DIR/$name.json"

  echo "Running $name"

  env \
    BASE_URL="$BASE_URL" \
    MODE="$mode" \
    VUS="$vus" \
    DURATION="$duration" \
    THINK_TIME_SEC="$THINK_TIME_SEC" \
    PAGE_SIZE="$PAGE_SIZE" \
    MAX_PAGES="$MAX_PAGES" \
    BOOK_ID="$BOOK_ID" \
    BOOK_SPECS="$BOOK_SPECS" \
    BOOK_COUNT="$BOOK_COUNT" \
    SRC_LANG="$SRC_LANG" \
    TGT_LANG="$TGT_LANG" \
    K6_SUMMARY_PATH="$out" \
    k6 run "$SCRIPT_PATH"

  node "$APPEND_RESULTS_SCRIPT" \
    "$out" \
    "$CSV_PATH" \
    "$ARCHITECTURE" \
    "$mode" \
    "$vus" \
    "$duration" \
    "$PROVIDER_MODE" \
    "$RUN_LABEL"
}

run_case catalog 4 2m catalog_v4_2m
run_case reader 4 2m reader_v4_2m
run_case translate 2 2m translate_v2_2m
run_case mixed 4 3m mixed_v4_3m

echo
echo "Done. Results written to:"
echo "  $RESULT_DIR"
