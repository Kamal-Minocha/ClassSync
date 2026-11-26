# ClassSync

A JavaFX desktop Learning Management System for a college in which **every lecture is a complete learning unit**: prepare before class, take part during it, revise after it.

**Stack:** Java 21 · JavaFX 21 (FXML + CSS) · direct JDBC · PostgreSQL 17 in Docker · Maven · JUnit 5. No ORM, no web frontend.

> **Project status (2026-11-26):** all 10 roadmap phases are complete and verified, plus an administrator dashboard for managing every kind of account — accounts, courses, lectures with upcoming-topic previews, CR delegation, study materials, timed quizzes with analytics and weak topics, temporary-code attendance, lecture feedback, reviewed student notes, the doubt forum with heatmap, announcements and in-app notifications, revision mode, performance and badges, the semester planner, the resource library, and bookmarks with a study tracker. 166 automated tests (29 unit + 137 integration) pass on the host, 26 real-UI checks pass (the offline Docker build last ran the 155 tests before the admin dashboard), and the desktop app also runs inside Docker via WSLg. Known limits are listed at the end of [docs/TESTING.md](docs/TESTING.md). See [docs/FEATURE_CHECKLIST.md](docs/FEATURE_CHECKLIST.md).

## Prerequisites (Windows)

* JDK 21 or newer (the build targets Java 21; JDK 24 was used during development)
* Docker Desktop (running)
* Nothing else — Maven is provided by the wrapper (`mvnw.cmd`)

## Setup

```powershell
copy .env.example .env                  # edit: replace every CHANGE_ME password
docker compose up -d postgres           # PostgreSQL 17 on 127.0.0.1:5432
docker compose ps                       # wait for (healthy)
.\mvnw compile exec:java@migrate        # create / upgrade tables
.\mvnw compile exec:java@demo-data      # OPTIONAL demo accounts and courses (empty DB only)
.\mvnw javafx:run                       # start ClassSync
```

### Demo accounts (only after `demo-data`)

All demo passwords are **`Demo@1234`** — demonstration only; only PBKDF2 hashes are stored.

| Sign in with | Role |
|---|---|
| `admin@college.edu` | Administrator — admin dashboard and User Management |
| `meera.iyer@college.edu` | Professor (CS301, CS305) |
| `arjun.menon@college.edu` | Professor (CS310) |
| `ravi.kumar@college.edu` or `CS2024002` | Class Representative — CR staff in CS301 (review notes, announcements, update lectures) and CS305 (defaults only) |
| `asha.rao@college.edu` or `CS2024001` | Student |

Demo schedules are generated relative to the day you run the command, so there are always past, today's and upcoming lectures. The demo also contains quizzes in every state: a closed CS301 quiz with released results (Asha is flagged weak on *Joins*), an open *Normalization quiz* Asha has not taken yet, a CS305 quiz waiting for Dr. Iyer to release results, an upcoming quiz and a draft. Past lectures have finalized attendance (Asha is below 75% in CS305; one corrected record in CS301 L2). Today's lectures are left open so Dr. Iyer can start a live attendance session from the lecture's *Attendance* tab. Feedback is open on CS301 L3 (anonymous) and L4; notes exist in every review state (Ravi has one in his review queue); the forum has resolved and open doubts; three announcements were published (one by Ravi as CR). Asha has a five-day revision streak, study tasks (one overdue) and bookmarks; CS301 has a semester plan with one overdue topic.

### Creating real accounts

Students can register themselves on the login screen (Student role only). Every other account is created by an **Administrator** in the app: *User Management* → **Add user** (Student, CR, Professor or Administrator), with edit, password reset, Student ↔ CR and disable/enable on each row.

The first administrator of a database is created with the local provisioning tool (migration-owner credentials), which can also do everything else from the command line:

```powershell
$env:CLASSSYNC_PROVISION_PASSWORD = "Welcome#2026"    # password for the NEW account (this window only)
.\mvnw compile exec:java@provision "-Dexec.args=admin 'Department Administrator' admin@college.edu"
.\mvnw compile exec:java@provision "-Dexec.args=professor 'Dr. Kavita Sharma' kavita.sharma@college.edu EMP205 'Computer Science'"
.\mvnw compile exec:java@provision "-Dexec.args=student 'Arjun Verma' arjun.verma@college.edu CS2024050 'Computer Science' 5"
.\mvnw compile exec:java@provision "-Dexec.args=cr 'Simran Kaur' simran.kaur@college.edu CS2024051 'Computer Science' 5"
.\mvnw compile exec:java@provision "-Dexec.args=set-role arjun.verma@college.edu CR"
.\mvnw compile exec:java@provision "-Dexec.args=reset-password simran.kaur@college.edu"
.\mvnw compile exec:java@provision "-Dexec.args=set-active arjun.verma@college.edu false"
```

Full procedures (including delegating CR rights in a course and changing database passwords):
[docs/ACCOUNTS_AND_CREDENTIALS.md](docs/ACCOUNTS_AND_CREDENTIALS.md).

## Configuration

Settings are read in this order (first wins): environment variables → `application.properties` → `.env` → built-in defaults. Both files are ignored by Git. Keys are listed in [.env.example](.env.example).

| Key | Default | Meaning |
|---|---|---|
| `CLASSSYNC_DB_URL` | `jdbc:postgresql://localhost:5432/classsync` | use host `postgres` inside Compose |
| `CLASSSYNC_APP_DB_USER` / `_PASSWORD` | `classsync_app` | restricted runtime user |
| `CLASSSYNC_MIGRATOR_USER` / `_PASSWORD` | `classsync_migrator` | schema owner (migrations, provisioning, tests) |
| `CLASSSYNC_TIMEZONE` | `Asia/Kolkata` | academic time zone for schedules and display |
| `CLASSSYNC_UPLOAD_DIR` | `uploads` | managed folder for uploaded file bytes |
| `CLASSSYNC_MAX_UPLOAD_MB` | `20` | upload size limit |
| `CLASSSYNC_ATTENDANCE_CODE_SECONDS` | `120` | how long each attendance code stays valid (15–900) |
| `CLASSSYNC_EMAIL_PATTERN` | `^[a-z0-9._-]+@college\.edu$` | allowed registration emails (format check only; ownership is not verified) |

## Tests

```powershell
.\mvnw test                                        # unit tests (no database)
.\mvnw verify                                      # + integration tests on the real classsync_test database
docker compose --profile test run --rm build-test  # the same inside Docker
```

Integration tests only ever touch `classsync_test`. Results and the full test-case list: [docs/TESTING.md](docs/TESTING.md).

## Documentation

| File | Contents |
|---|---|
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | approved plan and roadmap |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | layers, Mermaid diagram, login walkthrough, threading |
| [docs/DATABASE.md](docs/DATABASE.md) | ER diagram, tables, constraints, delete behaviour |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Student / CR / Professor permission matrix |
| [docs/DOCKER.md](docs/DOCKER.md) | Docker workflows, GUI profile, backup/restore, upgrades |
| [docs/TESTING.md](docs/TESTING.md) | test cases and actual results |
| [docs/FEATURE_CHECKLIST.md](docs/FEATURE_CHECKLIST.md) | what is done / pending |
| [docs/VIVA_GUIDE.md](docs/VIVA_GUIDE.md) | two-minute overview, beginner explanations per module, whole-project viva questions |
| [docs/ACCOUNTS_AND_CREDENTIALS.md](docs/ACCOUNTS_AND_CREDENTIALS.md) | every kind of login/password, how to create each user type, reset passwords, disable accounts, change database passwords |
| [docs/DESIGN.md](docs/DESIGN.md) | design language (Material Design 3), Thapar colour palette and where the campus photos are used |
| [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) | 15–20 minute live demo walkthrough for all roles |
| [docs/screenshots/](docs/screenshots/) | real screenshots captured from the running app |

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Cannot connect to the database" screen | Start Docker Desktop, `docker compose up -d postgres`, press Retry |
| "Database schema needs an update" | `.\mvnw compile exec:java@migrate` |
| `password authentication failed` | `.env` passwords differ from the ones used when the volume was first created; fix `.env` or `ALTER ROLE` |
| `invalid value for parameter "TimeZone"` | set `CLASSSYNC_TIMEZONE` to a modern zone name such as `Asia/Kolkata` |
| "The file is missing from the uploads folder" | the database row exists but `uploads/` was not restored — restore uploads with the database backup |

## Security and deployment notes

ClassSync is a desktop client that talks to a shared PostgreSQL database. Authorisation is enforced in the service layer and backed by database constraints, which suits a controlled college demonstration. A modified client binary could bypass client-side service checks, so this is not equivalent to a server-side security boundary. Uploaded files live in a local folder; multiple computers need the same shared uploads folder configured.

Icons are original SVG path drawings made for this project. The Thapar Institute logo and campus photographs in `src/main/resources/images/` (copied from `thapar images/`) belong to the institute; they are used for this college project — ask the institute before distributing ClassSync publicly.
