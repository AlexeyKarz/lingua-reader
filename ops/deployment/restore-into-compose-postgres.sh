#!/usr/bin/env bash

set -euo pipefail

DUMP_FILE="${1:-}"
DB_NAME="${DB_NAME:-mlr}"
DB_USER="${DB_USER:-mlr}"

if [[ -z "$DUMP_FILE" ]]; then
  echo "Usage: $0 <path-to-dump-file>"
  exit 1
fi

if [[ ! -f "$DUMP_FILE" ]]; then
  echo "Dump file not found: $DUMP_FILE"
  exit 1
fi

echo "Restoring $DUMP_FILE into compose postgres database '$DB_NAME'"

cat "$DUMP_FILE" | docker compose --profile db exec -T postgres \
  pg_restore \
  --clean \
  --if-exists \
  -U "$DB_USER" \
  -d "$DB_NAME"

echo "Restore completed."
