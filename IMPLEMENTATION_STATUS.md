# Implementation status

Last updated: 2026-11-14

## Completed

| Phase | Status |
|---|---|
| 0. Architecture, domain model, invariants | Done: `docs/architecture.md`, `docs/domain-model.md` |
| 1. Backend foundation: Spring Boot, Flyway schema, error handling, health | Done |
| 2. Payment lifecycle, simulator, fees, idempotency, history, audit | Done |
| 3. Double-entry ledger and settlement batching | Done |
| 4. Reconciliation: statements, matcher, exception queue, review | Done |
| 5. Frontend: all pages wired to the API | Done (see "Not verified") |
| 6. Integration and quality | Partly done (see below) |
| 7. Repository documentation, CI, Docker files | Done (see "Not verified") |

Additions beyond the original brief: a SQL-file reporting module with seven reports, CSV export and visible SQL; reporting views; database-level ledger guards (triggers); a Docker-free launcher using embedded PostgreSQL; an optional settlement scheduler.

Changes from the original brief: Spring Boot 4.1 instead of 3.x (3.x no longer receives open-source releases); embedded PostgreSQL instead of Testcontainers (no Docker daemon needed); a fifth reconciliation outcome, `DATE_MISMATCH`; no Spring Security (there is no login).

## Verified

Run on Windows 11, Temurin JDK 21.0.13, Node 24.15, on 2026-11-14.

| Check | Command | Result |
|---|---|---|
| Backend unit + integration tests | `./mvnw verify` | 123 tests, 0 failures, 0 errors; jar built |
| Flyway migrations V1 to V3 on PostgreSQL | part of the integration tests | applied; Hibernate schema validation passes |
| Demo data seeding and reset | `DemoDataIT` | passed, including ledger balance and cross-table money checks |
| Frontend type check | `npm run typecheck` | no errors |
| Frontend lint | `npm run lint` | no errors or warnings |
| Frontend tests | `npm test` | 40 tests passed |
| Frontend production build | `npm run build` | succeeded |

Defects found by the tests and fixed:

- Fractional amounts such as `10.50` were silently truncated to `10`, and unknown JSON fields such as `cardNumber` were silently ignored. Both are now rejected with `400`.
- Two events of one payment could receive the same timestamp on a coarse system clock, leaving their order undefined. The application clock is now strictly monotonic.
- Hibernate schema validation rejected fixed-width `CHAR` columns; they are now mapped explicitly.

## Not verified

These have been written but not exercised. Treat them as untested until someone runs them.

- **The UI against a live backend in a browser.** The frontend was verified by type checking, linting, component tests with a mocked API, and a production build, but the screens have not been opened and clicked through against the running API. The end-to-end demo walkthrough in the README has therefore not been performed by hand, and layout at desktop and mobile widths has not been inspected.
- **Docker.** Docker is not installed on the development machine. `docker-compose.yml`, both Dockerfiles and `frontend/nginx.conf` have never been built or run. The CI workflow includes a `docker compose build` job that will exercise the image builds on the first push.
- **GitHub Actions.** The workflow has not run, because the repository has not been pushed.
- **Deployment.** Nothing has been deployed. `docs/deployment.md` is guidance, not a record.
- **Swagger UI in a browser.** `/v3/api-docs` is asserted by a test; the Swagger UI page itself was not opened.

## Known limitations

Listed in the README under "Limitations". None are known defects.

## Next steps

1. Start the app (`./mvnw spring-boot:test-run` and `npm run dev`), walk through the demo, and fix anything the UI gets wrong.
2. Capture the screenshots listed in the README.
3. Push to GitHub and confirm all three CI jobs pass, in particular the Docker build.
4. Optionally deploy, following `docs/deployment.md`.
