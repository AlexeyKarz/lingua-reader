#!/usr/bin/env bash

set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-mlr}"
DB_USER="${DB_USER:-mlr}"
OUT_DIR="${OUT_DIR:-ops/deployment/dumps}"
OUT_FILE="${OUT_FILE:-$OUT_DIR/${DB_NAME}.dump}"

mkdir -p "$OUT_DIR"

echo "Creating custom-format dump at $OUT_FILE"
pg_dump \
  -Fc \
  -h "$DB_HOST" \
  -p "$DB_PORT" \
  -U "$DB_USER" \
  -d "$DB_NAME" \
  -f "$OUT_FILE"

echo "Dump written to $OUT_FILE"
