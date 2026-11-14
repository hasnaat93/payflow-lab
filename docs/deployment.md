# Deployment guide

PayFlow Lab has not been deployed anywhere yet. This guide describes how to do it; nothing here has been verified against a live hosting provider. Hosting plans, prices and free-tier limits change, so check the provider's current documentation rather than relying on this file.

**Before exposing it publicly:** it is a simulator with no authentication. Set `DEMO_RESET_ENABLED=false`, or anyone who finds the URL can wipe the data.

## What gets deployed

| Piece | Artifact | Needs |
|---|---|---|
| Database | PostgreSQL 15 or newer | a database and a user that may create tables, functions and triggers |
| Backend | `backend/Dockerfile` (or the jar from `./mvnw package`) | Java 21, about 512 MB RAM |
| Frontend | `frontend/Dockerfile` (nginx) or the static `frontend/dist` | a way to forward `/api` to the backend |

Any platform that runs containers and offers managed PostgreSQL works. The application is portable: it is configured only through environment variables.

## Environment variables

### Backend

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `DATABASE_URL` | yes | `jdbc:postgresql://localhost:5432/payflow` | JDBC URL. Must start with `jdbc:postgresql://`. Some providers give a `postgres://user:pass@host/db` URL: convert it and pass the credentials separately. |
| `DATABASE_USERNAME` | yes | `payflow` | |
| `DATABASE_PASSWORD` | yes | `payflow` | A secret. Set it in the platform's secret store, never in the repository. |
| `PORT` | no | `8080` | HTTP port. |
| `CORS_ALLOWED_ORIGINS` | only if the browser calls the API on a different origin | `http://localhost:5173` | Comma-separated exact origins. Never `*`. |
| `DEMO_SEED_ON_STARTUP` | no | `true` | Loads the demo dataset when the database is empty. |
| `DEMO_RESET_ENABLED` | no | `true` (`false` under the `prod` profile) | Enables `POST /api/v1/demo/reset`. |
| `SETTLEMENT_SCHEDULE_ENABLED` | no | `false` | Daily settlement of the previous UTC day. |
| `SETTLEMENT_SCHEDULE_CRON` | no | `0 30 0 * * *` | Spring cron, evaluated in UTC. |
| `SPRING_PROFILES_ACTIVE` | recommended | none | Set to `prod` for JSON logs and reset disabled by default. |

### Frontend (nginx image)

| Variable | Default | Purpose |
|---|---|---|
| `API_UPSTREAM` | `http://backend:8080` | Where nginx forwards `/api/`. Use the backend's internal or public URL. |

Nothing secret is ever put in the frontend bundle. The frontend has no build-time API URL: it always calls `/api/v1` on its own origin.

## Build commands

```bash
# backend jar
cd backend && ./mvnw -B package -DskipTests      # target/payflow-lab-backend-1.0.0.jar

# frontend static files
cd frontend && npm ci && npm run build           # dist/

# or both images
docker compose build
```

## Deployment order

1. **Create the database.** An empty PostgreSQL database and a user that owns it.
2. **Deploy the backend** with the database variables. On first start Flyway creates the schema (`V1` core tables, `V2` ledger with triggers, `V3` reporting views), then the demo data is seeded if enabled.
3. **Check health:** `GET /actuator/health` returns `{"status":"UP"}`. Use `/actuator/health/readiness` for the platform's readiness probe and `/actuator/health/liveness` for liveness.
4. **Deploy the frontend** with `API_UPSTREAM` pointing at the backend.
5. **Smoke test:** open the UI, check the overview loads, create and capture a payment, run settlement.

## Database migrations

Migrations run automatically at startup and are forward-only. Hibernate is set to `validate`, so the application refuses to start if the schema does not match the code. To change the schema, add a new `V<n>__description.sql` file; never edit a migration that has been applied anywhere.

## Two ways to connect the frontend and backend

**Same origin (recommended).** Serve the UI with the provided nginx image and let it proxy `/api/`. No CORS configuration is needed.

**Separate origins.** If the UI is on a static host and the API on another domain, the static host must rewrite `/api/*` to the backend. If it cannot, the browser will call the backend's origin directly: in that case add the UI's exact origin to `CORS_ALLOWED_ORIGINS` and change `BASE` in `frontend/src/lib/api.ts` to the backend URL. The static host also needs a single-page-app fallback (all unknown paths serve `index.html`).

## Demo data strategy

- A public demo: keep `DEMO_SEED_ON_STARTUP=true` and set `DEMO_RESET_ENABLED=false`. To refresh the data, empty the database (or restart with a fresh one) and restart the backend.
- The seeder builds ten days of history relative to the day it runs, so an old demo slowly ages out of the dashboard's default 14-day window. Re-seed periodically.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Backend exits with `Schema validation` errors | The database has a schema from a different version. Use an empty database. |
| Backend exits with `permission denied` during migration | The database user cannot create functions or triggers. |
| `Connection refused` at startup | `DATABASE_URL` host or port is wrong, or the database is not ready; platforms without start ordering need a restart policy. |
| UI loads but every screen says it cannot reach the API | `API_UPSTREAM` is wrong, or the static host is not forwarding `/api`. |
| Browser console shows a CORS error | The UI is calling the API cross-origin and its origin is not in `CORS_ALLOWED_ORIGINS`. |
| Refreshing a page such as `/transactions` returns 404 | The host has no single-page-app fallback. |
| Dashboard is empty | Seeding is off, or the demo data is older than the selected date range. |

## Not covered

TLS termination, backups, monitoring and scaling are left to the hosting platform. This project is a simulator and is not intended to run as a production service.
