#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 <add_book.py arguments>" >&2
  echo "Example: $0 --work \"Metamorphosis\" --original-lang de --edition \"lang=de,file=classpath:books/metamorphosis_de.epub\" --edition \"lang=en,file=classpath:books/metamorphosis_en.epub\"" >&2
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

if grep -Eq '^[[:space:]]*MLR_ADMIN_ENABLED[[:space:]]*=[[:space:]]*false[[:space:]]*$' "$ENV_FILE"; then
  echo "Warning: MLR_ADMIN_ENABLED=false in $ENV_FILE; ingestion API calls will fail unless the app is already configured differently." >&2
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest build ingest-runner
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest run --rm ingest-runner "$@"
