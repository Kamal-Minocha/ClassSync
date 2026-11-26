# ClassSync — Implementation Plan (Phase 1)

> **Status (2026-11-25):** all 10 roadmap phases are implemented and verified. Results: [TESTING.md](TESTING.md); feature states: [FEATURE_CHECKLIST.md](FEATURE_CHECKLIST.md). Deviations from this plan are recorded where they happened (e.g. no `activity_events` table — see DATABASE.md).

> Status: **Approved** — the project owner instructed "start implementing the project section by section" on 2026-11-24 after providing `CLASSSYNC_MASTER_PROMPT.md`. That instruction is recorded here as the Section 16 approval.

## 1. Understanding

ClassSync is a JavaFX desktop Learning Management System for a college. Every **lecture** is a complete learning unit:

| Stage | What the student sees | Backing records |
|---|---|---|
| Before | Upcoming topic, objectives, preparation instructions, preparation materials | `lectures` (status `PUBLISHED`, future `starts_at`), `materials` linked to the lecture |
| During | Lecture materials, live quiz, temporary-code attendance, doubts | `quizzes`, `attendance_sessions`, `discussions` |
| After | Short quiz, feedback, approved notes, incorrect-answer review, revision, progress | `quiz_attempts`, `feedback`, `materials` (kind `NOTE`), `revision_history`, `activity_events` |

## 2. Technology confirmation

* Java 21 (compiled with `--release 21`; the local machine has JDK 24 which compiles to the 21 target), JavaFX/OpenJFX 21.0.13 LTS, JavaFX CSS and Charts.
* Maven 3.9 with Maven Wrapper.
* **PostgreSQL 17 only**, accessed via direct JDBC (`org.postgresql:postgresql` 42.7.x). MySQL is replaced everywhere.
* Docker Compose runs PostgreSQL with a health check and a named volume, bound to `127.0.0.1`.
* JUnit 5 + integration tests against a real, dedicated PostgreSQL test database (`classsync_test`).
* Excluded: Spring, Hibernate/JPA/any ORM, web frontends, WebView substitutes, REST frameworks, Node/PHP/Python backends, Firebase/Supabase/MongoDB, MySQL/MariaDB, H2/SQLite.

## 3. Agent policy

Zero sub-agents. One implementation agent (this one) works sequentially, one module at a time.

## 4. Features and simplest practical implementation

| # | Module | Tier | Simplest implementation |
|---|---|---|---|
| 1 | Authentication | Essential | `users` + `students`/`professors`; PBKDF2 hashes; `Session` singleton holding the signed-in `User`; registration only creates `STUDENT` |
| 2 | Dashboards | Essential→expanded | One dashboard view per role built from cards; each card is a single aggregate SQL query in a DAO; cards appear only when their module exists |
| 3 | Courses & lectures | Essential | `courses`, `enrollments`, `lectures` with `DRAFT/PUBLISHED/CANCELLED/COMPLETED`; archive flag instead of delete when history exists |
| 4 | Materials | Essential | `materials` row + managed file under `uploads/materials/<uuid>.<ext>`; FileChooser; extension allowlist + magic-byte check |
| 5 | Quizzes | Essential | `quizzes/questions/question_options/quiz_attempts/answers`; deadline computed in SQL with `now()`; idempotent submit via conditional `UPDATE ... WHERE status='IN_PROGRESS'` |
| 6 | Attendance | Essential | `attendance_sessions` storing a SHA-256 hash of a `SecureRandom` code; `attendance` unique per (lecture, student); finalize inserts ABSENT rows; corrections table |
| 7 | Feedback | Essential | `feedback` unique per (lecture, student); anonymous flag copied from lecture; report queries never select `student_id` for anonymous rows |
| 8 | Notes & approval | Essential | Notes are `materials` with `kind='NOTE'` and `review_status`; `material_approvals` history; `notes_votes` unique |
| 9 | Doubts | Essential (heatmap later) | `discussions`, `discussion_replies`, `reply_votes`; resolve by author or professor or delegated CR |
| 10 | Announcements & notifications | Essential | `announcements` + `notifications` with `dedupe_key` unique per user; refresh on navigation + 60 s timer |
| 11 | Revision mode | Advanced | Completed lectures list + released wrong answers + `PRACTICE` attempts + `revision_history` |
| 12 | Performance & achievements | Advanced | SQL aggregates + LineChart/BarChart; badges awarded by deterministic rules into `student_achievements` (unique) |
| 13 | Semester planner | Advanced | `teaching_plans` rows linked to `topics` and optionally a `lecture_id` |
| 14 | Resource library | Advanced | A filtered view over `materials` (kinds `MATERIAL/PYQ/REFERENCE/NOTE` approved) — no separate storage |
| 15 | Bookmarks & study tracker | Advanced | `bookmarks` with real FKs + CHECK exactly one target; `study_tasks` private per student |

## 5. Layers

See [ARCHITECTURE.md](ARCHITECTURE.md) for the diagram and the worked login example.

`JavaFX View → Controller → Service → DAO → JDBC → PostgreSQL`

## 6. Data model

See [DATABASE.md](DATABASE.md) — Mermaid ER diagram, tables, constraints, delete behaviour.

## 7. Permissions

See [PERMISSIONS.md](PERMISSIONS.md).

## 8. Maven structure and versions

| Item | Version | Why |
|---|---|---|
| Java release | 21 | LTS required by the brief |
| OpenJFX (`javafx-controls`, `javafx-fxml`) | 21.0.13 | Latest patch of the JavaFX 21 LTS line; runs on JDK 21+ |
| PostgreSQL JDBC | 42.7.15 | Current stable driver, supports PostgreSQL 8.4–17+ |
| JUnit Jupiter | 5.14.4 | Stable JUnit 5 line |
| Surefire / Failsafe | 3.5.x | Unit tests (`*Test`) via `mvn test`; DB integration tests (`*IT`) via `mvn verify` |
| javafx-maven-plugin | 0.0.8 | `mvn javafx:run` launch on Windows |
| exec-maven-plugin | 3.5.x | `mvn exec:java@migrate` for the migration tool |

No other runtime libraries. Icons are drawn with JavaFX `SVGPath` shapes written in this project (no icon library needed).

## 9. UI system

* Original palette: navy `#0f172a`, indigo `#4f46e5`, violet `#7c3aed`, teal `#14b8a6`, cyan `#06b6d4`, off-white `#f8fafc`; dark theme uses `#0b1020` surfaces. Tokens are looked-up colours in `base.css`, overridden by `light.css`/`dark.css`.
* Shell: `BorderPane` — top bar (brand, scoped search, notifications bell with unread count, theme toggle, profile menu with logout), collapsible left sidebar (role-relevant items), centre `StackPane` content host with loading overlay.
* Reusable components (`view/components`): `Card`, `StatCard`, `Badge`, `EmptyState`, `FormRow`, `Toast` notice, `LoadingOverlay`, `Icons`.
* Screens are built in straightforward Java layout code (one `*View` class per screen with its controller logic kept thin). FXML is used for the login screen to demonstrate FXML + controller binding.

## 10. Docker strategy

* `compose.yaml`: service `postgres` (`postgres:17`), health check `pg_isready`, named volume `classsync_pgdata`, port `127.0.0.1:${DB_PORT}:5432`.
* `docker/postgres-init/01-init.sh` (first run only, empty volume): creates the restricted application role `classsync_app` and the disposable `classsync_test` database.
* Schema changes: versioned files `database/migrations/V00N__name.sql`, applied by `com.classsync.tools.Migrate` using the **owner** credentials and tracked in `schema_migrations` (version + checksum). Never requires deleting the volume.
* `Dockerfile` (multi-stage): `build` stage compiles and runs tests (`docker compose --profile test run --rm build-test`); `gui` stage installs OpenJFX native deps (GTK, X11/WSLg libs) for the opt-in `gui` profile.
* Windows everyday mode: Postgres in Docker, `mvnw javafx:run` on the host.

## 11. Syllabus mapping

See the Unit 4 / Unit 5 table in [VIVA_GUIDE.md](VIVA_GUIDE.md). PostgreSQL substitutes for the syllabus's MySQL example; all JDBC concepts (driver, `DriverManager`, `Connection`, `PreparedStatement`, `ResultSet`, transactions) are identical, but the project does not demonstrate a MySQL connection.

## 12. Roadmap and acceptance

| Phase | Exit criteria |
|---|---|
| 1 Plan | These documents |
| 2 DB/JDBC foundation | Healthy container; migration tool applies V001; `UserDAO` CRUD + rollback IT passes |
| 3 JavaFX foundation | App launches, shell + login render, theme toggle, resize |
| 4 Auth & role shells | 3 roles sign in; registration creates STUDENT only; bad credentials rejected |
| 5 Learning loop | Professor publishes lecture + material; enrolled student sees preview and downloads; survives restart |
| 6 Quizzes | Timed attempt persists and scores; expiry/idempotency/answer isolation tested |
| 7 Attendance | Code flow, finalize, correction audit; concurrency boundaries tested |
| 8 Collaboration | Feedback, notes review, doubts, announcements, notifications |
| 9 Advanced | Revision, performance, planner, library, bookmarks/tasks, streaks, badges, heatmap |
| 10 Handover | Checklist reconciled, screenshots, viva guide, demo script |

**Smallest working version first:** Phases 2–5 (sign in as professor → publish lecture with preparation material → sign in as student → see upcoming preview → download).

## 13. Assumptions

* Academic timezone defaults to `Asia/Kolkata` (configurable `classsync.timezone`).
* College email format defaults to `^[a-z0-9._-]+@college\.edu$` (configurable `classsync.emailPattern`). No email ownership verification is performed.
* Enrollment number format: `^[A-Z0-9]{6,15}$`.
* One professor per course (the creator). Multi-professor courses would add rows to `course_staff` with a `PROFESSOR` staff role later.
* Upload limit 20 MB; uploads root `./uploads` (configurable).
* Git: the folder is not a git repository; no commits are made unless requested.
