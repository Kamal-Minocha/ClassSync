# ClassSync — Docker Guide

Versions verified on 2026-11-24: Docker Engine 29.9, Compose v5.7, image `postgres:17`, build image `maven:3.9-eclipse-temurin-21-noble`, GUI runtime `eclipse-temurin:21-jre-noble`.

## 1. First start (Windows, PowerShell)

```powershell
copy .env.example .env          # then replace every CHANGE_ME value with your own passwords
docker compose up -d postgres   # starts PostgreSQL 17 in the background
docker compose ps               # wait until STATUS shows (healthy)
.\mvnw compile exec:java@migrate   # creates/updates the tables (runs as the migration owner)
```

Optional demo world (only into an **empty** database):

```powershell
.\mvnw compile exec:java@demo-data
```

Then launch the desktop app on the host:

```powershell
.\mvnw javafx:run
```

### What happens on the very first start

`docker/postgres-init/01-init.sh` runs **only when the data volume is empty**. It creates:

| Role / database | Purpose |
|---|---|
| `postgres` (bootstrap superuser, `POSTGRES_PASSWORD`) | container administration only |
| `classsync_migrator` | owns the schema; used by `Migrate`, `Provision`, `DemoData` and the tests |
| `classsync_app` | restricted runtime user: SELECT/INSERT/UPDATE/DELETE only, no DDL, cannot delete audit rows |
| database `classsync` | the real application data |
| database `classsync_test` | disposable database wiped by integration tests |

Changing passwords in `.env` later does **not** change existing roles; use `ALTER ROLE ... PASSWORD` inside the container.

## 2. Schema updates for existing installations

Never delete the volume to update the schema. Pull the new code, then:

```powershell
.\mvnw compile exec:java@migrate -Dexec.args=status   # shows PENDING migrations
.\mvnw compile exec:java@migrate                      # applies them, each in its own transaction
```

`schema_migrations` records version + SHA-256 checksum. Editing an applied migration file is refused — add a new `V00N__*.sql` instead. `database/schema.sql` is generated from the migrations (`-Dexec.args=schema`) for reading and reviews.

## 3. Addresses: host vs container

| Where Java runs | JDBC URL |
|---|---|
| Windows/macOS/Linux host | `jdbc:postgresql://localhost:5432/classsync` (port bound to `127.0.0.1` only) |
| Inside Compose (`build-test`, `classsync-gui`) | `jdbc:postgresql://postgres:5432/classsync` (service name) |

Java on the host does not receive `.env` automatically from Compose; `AppConfig` reads `.env` itself as its lowest-priority source. Environment variables override it.

## 4. Build and test in a container

```powershell
docker compose --profile test run --rm build-test
```

Multi-stage `Dockerfile`, target `build`: compiles, packages and runs the unit tests during the image build (which also downloads the JUnit test providers); the container then runs `mvn verify` (unit + integration tests) against `classsync_test` on the `postgres` service. After the image is built, the test run needs no internet access (`mvn -o verify` works).

## 5. Desktop GUI in a container (opt-in)

The image target `gui` contains JRE 21, the Linux OpenJFX jars and the native libraries JavaFX needs (GTK 3, Mesa/OpenGL, X11 extensions, fonts). JavaFX uses software rendering (`-Dprism.order=sw`). The UI is the same native JavaFX app — there is no browser version.

**Windows 11 with Docker Desktop (WSL 2 backend) — verified 2026-11-25, no Linux distribution needed.** Docker Desktop exposes the WSLg display to containers under `/run/desktop/mnt/host/wslg`. From PowerShell in the project folder:

```powershell
$env:X11_SOCKET_DIR = "/run/desktop/mnt/host/wslg/.X11-unix"
$env:WSLG_DIR = "/run/desktop/mnt/host/wslg"
$env:DISPLAY = ":0"
docker compose --profile gui up -d --build classsync-gui    # a "ClassSync (docker-desktop)" window opens
docker compose --profile gui stop classsync-gui             # or simply close the window
```

The containerised app connects to the `postgres` service by name as the restricted `classsync_app` role; screenshot `docs/screenshots/90-docker-gui-wslg.png` was captured from inside the Linux display (`xwd`) on this machine.

**Windows 11 with a WSL 2 Ubuntu distribution** (run from the Ubuntu shell inside the project folder):

```bash
export DISPLAY=:0 WSLG_DIR=/mnt/wslg
docker compose --profile gui up --build classsync-gui
```

**Linux with X11:**

```bash
xhost +local:            # allow local containers to use your X server (revert with xhost -local:)
docker compose --profile gui up --build classsync-gui
```

Uploaded files are stored in the named volume `classsync_uploads` (mounted at `/data/uploads`).

Limitations: Docker does not provide a display by itself; macOS needs XQuartz and is not covered; Windows 10 without WSLg cannot show the window. File choosers inside the container see only the container's file system (e.g. `/data/uploads`), not your Windows folders. **Status: verified on Windows 11 + Docker Desktop + WSLg (2026-11-25); the Linux/X11 and Ubuntu-on-WSL variants are documented but were not run here.**

## 6. Backup and restore (database + uploads together)

Always back up both — PostgreSQL holds metadata, the uploads folder holds the file bytes.

> **Do not use `> file` redirection for the dump in Windows PowerShell.** PowerShell 5.1 re-encodes redirected
> output as text, so `pg_dump ... -Fc > backups\classsync.dump` produces a file that `pg_restore` rejects
> ("input file does not appear to be a valid archive" — reproduced on 2026-11-25). Write the dump inside the
> container and copy the file out instead:

```powershell
mkdir backups -Force
docker compose exec -T postgres pg_dump -U postgres -d classsync -Fc -f /tmp/classsync.dump
docker compose cp postgres:/tmp/classsync.dump backups\classsync.dump
docker compose exec -T postgres rm /tmp/classsync.dump
Compress-Archive -Path uploads -DestinationPath backups\uploads.zip -Force
```

**Check a backup without touching real data** — restore it into a throw-away database, compare, drop it
(verified on 2026-11-25: users, quizzes, attendance, notifications and materials counts identical):

```powershell
docker compose cp backups\classsync.dump postgres:/tmp/restore.dump
docker compose exec -T postgres createdb -U postgres classsync_restore_check
docker compose exec -T postgres pg_restore -U postgres -d classsync_restore_check /tmp/restore.dump
docker compose exec -T postgres psql -U postgres -d classsync_restore_check -c "SELECT count(*) FROM users"
docker compose exec -T postgres dropdb -U postgres classsync_restore_check
```

**Restore over the real database** (this REPLACES current data — stop ClassSync first, and only do it on purpose):

```powershell
docker compose cp backups\classsync.dump postgres:/tmp/restore.dump
docker compose exec -T postgres pg_restore -U postgres -d classsync --clean --if-exists /tmp/restore.dump
Expand-Archive backups\uploads.zip -DestinationPath . -Force
```

If you use the GUI container, back up the `classsync_uploads` volume instead:
`docker run --rm -v classsync_classsync_uploads:/data -v ${PWD}/backups:/b alpine tar czf /b/uploads.tgz -C /data .`

## 7. Useful commands

| Task | Command |
|---|---|
| Stop the database (data kept) | `docker compose stop postgres` |
| Logs | `docker compose logs -f postgres` |
| psql shell | `docker compose exec postgres psql -U postgres -d classsync` |
| Remove containers (data kept in volume) | `docker compose down` |
| **Destroy all data** (only if you really mean it) | `docker compose down -v` |
