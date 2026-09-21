#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HOST_MANIFEST_DIR="${INGESTION_MANIFEST_DIR:-./ops/ingestion}"

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 <manifest path or manifest filename> [--dry-run]" >&2
  echo "Example: $0 books.local.json" >&2
  exit 2
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

if grep -Eq '^[[:space:]]*MLR_ADMIN_ENABLED[[:space:]]*=[[:space:]]*false[[:space:]]*$' "$ENV_FILE" \
  && ! grep -Eq '^[[:space:]]*(MLR_ADMIN_API_TOKEN|MLR_ADMIN_TOKEN)[[:space:]]*=[[:space:]]*[^[:space:]#]+' "$ENV_FILE"; then
  echo "Warning: MLR_ADMIN_ENABLED=false and no MLR_ADMIN_API_TOKEN is set in $ENV_FILE; ingestion API calls need an admin:ingest API token." >&2
fi

manifest="$1"
shift
container_manifest="$manifest"
manifest_prefix="${HOST_MANIFEST_DIR%/}/"
if [[ "$manifest" != /* && -f "$HOST_MANIFEST_DIR/$manifest" ]]; then
  container_manifest="/ingestion/$manifest"
fi
if [[ "$manifest" == "$manifest_prefix"* ]]; then
  container_manifest="/ingestion/${manifest#"$manifest_prefix"}"
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest build ingest-runner
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest run --rm --entrypoint python ingest-runner \
  python-embed/add_books_from_manifest.py "$container_manifest" "$@"
