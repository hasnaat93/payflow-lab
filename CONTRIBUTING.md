# Contributing

Thanks for looking at PayFlow Lab. It is a simulator built for learning, so clear, well-tested changes matter more than new features.

## Local development

You need JDK 21 and Node 20 or newer. Docker is optional.

```bash
# backend on :8080, with an embedded PostgreSQL on :54329 and demo data
cd backend
./mvnw spring-boot:test-run

# frontend on :5173 (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

To use your own PostgreSQL instead, set `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` and run `./mvnw spring-boot:run`.

## Before opening a pull request

```bash
cd backend  && ./mvnw verify
cd frontend && npm run typecheck && npm run lint && npm test && npm run build
```

CI runs the same commands.

## Ground rules

- **Money is integer minor units.** No `double`, `float`, `BigDecimal` division or `parseFloat` on amounts.
- **Every money-moving change needs a test**, including the failure path.
- **State changes go through `Transaction`'s methods** so the state machine is always checked.
- **The ledger is append-only.** Fix mistakes with a compensating entry.
- **Schema changes are new Flyway migrations.** Never edit one that already exists.
- **Synthetic data only.** No real card numbers, customer data, company names or credentials, in code, tests, fixtures or screenshots.
- **Do not overstate.** Documentation must not claim certification, compliance, real network connectivity or results that were not measured.

## Project layout

`backend/` Spring Boot application · `frontend/` React app · `docs/` architecture, data model, API examples and deployment notes.
