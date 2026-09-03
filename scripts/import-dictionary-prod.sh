#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 <import_dictionary.py arguments>" >&2
  echo "Example: $0 --jsonl /dictionaries/kaikki.org-dictionary-German.jsonl.gz --source de --target en --work \"Metamorphosis\" --body-only --missing-only" >&2
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

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest build ingest-runner
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --profile ingest run --rm --entrypoint python ingest-runner \
  python-embed/import_dictionary.py "$@"
