#!/bin/bash
# Runs ONCE, only when the postgres data volume is empty (Docker entrypoint behaviour).
# Creates: migration owner role, restricted app role, the classsync database and the
# disposable classsync_test database. Tables are NOT created here: run the migrations.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v migrator="$CLASSSYNC_MIGRATOR_USER" -v migrator_pw="$CLASSSYNC_MIGRATOR_PASSWORD" \
  -v app="$CLASSSYNC_APP_DB_USER" -v app_pw="$CLASSSYNC_APP_DB_PASSWORD" <<'SQL'
CREATE ROLE :"migrator" LOGIN PASSWORD :'migrator_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE :"app"      LOGIN PASSWORD :'app_pw'      NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE classsync      OWNER :"migrator";
CREATE DATABASE classsync_test OWNER :"migrator";
SQL

for db in classsync classsync_test; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" \
    -v migrator="$CLASSSYNC_MIGRATOR_USER" -v app="$CLASSSYNC_APP_DB_USER" <<'SQL'
ALTER SCHEMA public OWNER TO :"migrator";
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app";
SQL
done

echo "ClassSync roles and databases created. Next: mvnw exec:java@migrate"
