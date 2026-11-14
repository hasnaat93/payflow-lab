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
| 5. Frontend: all pages wired to the API | Done |
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
| UI against the live API, in a headless browser (Edge, 1440x900 and 390x844) | scripted walkthrough: overview and currency filter, merchant, new payment 49.99, capture (fee 1.75, net 48.24), partial refund, blocked over-refund, settlement run and re-run, batch detail, statement generation, reconciliation, exception review, ledger, reports with SQL and CSV, audit, fee plans, about, mobile layouts and menu | 27 of 27 checks passed; no console errors and no failed API calls |
| GitHub Actions CI (first push) | backend tests, frontend checks, `docker compose build` | all three jobs passed on Linux |

Defects found by the tests and fixed:

- Fractional amounts such as `10.50` were silently truncated to `10`, and unknown JSON fields such as `cardNumber` were silently ignored. Both are now rejected with `400`.
- Two events of one payment could receive the same timestamp on a coarse system clock, leaving their order undefined. The application clock is now strictly monotonic.
- Hibernate schema validation rejected fixed-width `CHAR` columns; they are now mapped explicitly.

## Not verified

These have been written but not exercised. Treat them as untested until someone runs them.

- **Running the Docker stack.** Docker is not installed on the development machine. GitHub Actions builds both images successfully (`docker compose build`), but `docker compose up` has never been run, so the containers, the nginx proxy and the compose wiring are unproven at runtime.
- **Deployment.** Nothing has been deployed. `docs/deployment.md` is guidance, not a record.
- **Swagger UI in a browser.** `/v3/api-docs` is asserted by a test and `/swagger-ui.html` returns 200, but the page was not looked at.
- **Hover and keyboard interaction.** The walkthrough clicked through every flow but did not test keyboard-only navigation or chart tooltips.
- **The browser walkthrough is not in the repository** as an automated test; it was a one-off script.

## Known limitations

Listed in the README under "Limitations". None are known defects.

## Next steps

1. Run `docker compose up --build` on a machine with Docker and walk through the demo there.
2. Optionally deploy, following `docs/deployment.md`.
