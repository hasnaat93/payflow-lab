#!/bin/sh
# Entry point of the single-container demo image: starts a private PostgreSQL, then the
# application. The database lives in the container and is recreated on every start, so a
# demo instance always comes up with fresh demo data and needs no external services.
set -eu

export PGDATA=/tmp/pgdata
rm -rf "$PGDATA"
initdb --username=app --auth=trust --encoding=UTF8 --no-sync >/dev/null

# Small and non-durable on purpose: this database is disposable and shares 512 MB with a JVM.
pg_ctl --pgdata="$PGDATA" --wait --log=/tmp/postgres.log start --options="\
 -c listen_addresses=127.0.0.1 -c max_connections=25 -c shared_buffers=24MB -c work_mem=2MB \
 -c maintenance_work_mem=16MB -c fsync=off -c synchronous_commit=off -c full_page_writes=off \
 -c wal_level=minimal -c max_wal_senders=0 -c max_wal_size=64MB -c autovacuum_max_workers=1"

export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/postgres"
export DATABASE_USERNAME=app
export DATABASE_PASSWORD=unused
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=8

exec java $JAVA_DEMO_OPTS -jar /app/app.jar
