#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"
HEALTHCHECK_URL="${HEALTHCHECK_URL:-http://127.0.0.1:8081/api/catalog/books}"
HEALTHCHECK_RETRIES="${HEALTHCHECK_RETRIES:-30}"
HEALTHCHECK_DELAY_SECONDS="${HEALTHCHECK_DELAY_SECONDS:-5}"

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

echo "Waiting for application healthcheck: $HEALTHCHECK_URL"
for attempt in $(seq 1 "$HEALTHCHECK_RETRIES"); do
  if curl --fail --silent --show-error "$HEALTHCHECK_URL" >/dev/null; then
    echo "Healthcheck passed on attempt $attempt/$HEALTHCHECK_RETRIES"
    break
  fi

  if [[ "$attempt" -eq "$HEALTHCHECK_RETRIES" ]]; then
    echo "Healthcheck failed after $HEALTHCHECK_RETRIES attempts" >&2
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps >&2
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail=100 app web >&2
    exit 1
  fi

  echo "Healthcheck attempt $attempt/$HEALTHCHECK_RETRIES failed; retrying in ${HEALTHCHECK_DELAY_SECONDS}s"
  sleep "$HEALTHCHECK_DELAY_SECONDS"
done

docker image prune -f --filter "until=168h" >/dev/null

echo "Deployment completed. Healthcheck passed: $HEALTHCHECK_URL"
