# Web UI

Shared frontend for both backend architectures:

- monolith
- microservices via the API gateway

## Stack

- React
- TypeScript
- Vite

Main views:

- `Catalog`
- `Reader`

## Run

```bash
npm install
npm run dev
```

Default local target:

- backend or gateway on `http://localhost:8080`

## Notes

- The frontend is not part of the benchmark target budget.
- For private VM deployment, the frontend is kept local and SSH/IAP tunnels to the backend are used.