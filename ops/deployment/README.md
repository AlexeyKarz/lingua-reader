# Private GCE Deployment

This is the original private VM deployment path used for the thesis benchmark work. For the maintained showcase deployment, prefer the root `docker-compose.prod.yml`, which includes the frontend container and publishes only the frontend/proxy port.

This older deployment path is still useful for private benchmarking:

- one private Google Compute Engine VM
- Docker + Docker Compose
- PostgreSQL on the same VM
- one stack running at a time
- access through SSH/IAP tunnels, not public service exposure

## 1. VM

First target:

- machine type: `e2-standard-2`
- OS: Ubuntu LTS
- no public app exposure

The VM should remain private when using this benchmark deployment shape because it may include:

- admin ingestion endpoints when explicitly enabled
- provider API keys in environment
- Grafana/Prometheus dashboards for internal use

## 2. Install Docker

Install Docker Engine + Compose plugin on the VM, then clone the repo.

## 3. Dump the local dataset

From the monolith repo on your local machine:

```bash
chmod +x ops/deployment/dump-local-db.sh
./ops/deployment/dump-local-db.sh
```

Default output:

```text
ops/deployment/dumps/mlr.dump
```

The script creates a PostgreSQL custom-format dump with `pg_dump -Fc`.
The deployment Postgres version should match the local dump tool version; this setup uses PostgreSQL 17 with `pgvector` enabled.
The dump file itself is a local deployment artifact and is intentionally **not** committed to Git. The repository keeps the dump/restore scripts and the methodology, not the binary dump blob.

## 4. Copy the dump to the VM

Example with IAP:

```bash
gcloud compute scp \
  --zone <ZONE> \
  --tunnel-through-iap \
  ops/deployment/dumps/mlr.dump \
  <VM_NAME>:~/mlr.dump
```

## 5. Start the private monolith stack

On the VM, create a local `.env` file in the repo root. For the first monolith deployment, use the compose Postgres service:

```env
DB_HOST=postgres
DB_PORT=5432
DB_NAME=mlr
DB_USER=mlr
DB_PASSWORD=mlr_password
OPENAI_API_KEY=
GOOGLE_API_KEY=
TRANSLATE_PROVIDER=stub
MLR_ADMIN_ENABLED=false
MLR_ADMIN_API_TOKEN=
MLR_ADMIN_TOKEN=
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus
```

Then start the deployment profile:

```bash
docker compose \
  --profile db \
  -f docker-compose.yml \
  -f docker-compose.deployment.yml \
  up -d --build
```

## 6. Restore the dump into VM Postgres

On the VM:

```bash
chmod +x ops/deployment/restore-into-compose-postgres.sh
./ops/deployment/restore-into-compose-postgres.sh ~/mlr.dump
```

This restores the dump into the compose-managed `postgres` container.

## 7. Private access / tunnels

Use IAP SSH port forwarding instead of public firewall exposure:

```bash
gcloud compute ssh <VM_NAME> \
  --zone <ZONE> \
  --tunnel-through-iap \
  -- \
  -L 8080:localhost:8080 \
  -L 3000:localhost:3000 \
  -L 9090:localhost:9090
```

Then access locally:

- app/backend: `http://localhost:8080`
- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`

## 8. First verification

Check:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:9090/-/ready
```

## Notes

- Keep this deployment private; do not expose ingestion/Grafana/Prometheus publicly
- After deploying monolith, deploy microservices against the same private DB and run the capped benchmark matrix
