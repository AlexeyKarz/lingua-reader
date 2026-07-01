#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTHCHECK_URL="${HEALTHCHECK_URL:-http://127.0.0.1:8081/api/catalog/books}"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build --remove-orphans postgres app web
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps

curl --fail --silent --show-error "$HEALTHCHECK_URL" >/dev/null

docker image prune -f --filter "until=168h" >/dev/null

echo "Deployment completed. Healthcheck passed: $HEALTHCHECK_URL"
