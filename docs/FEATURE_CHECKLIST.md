# ClassSync — Feature Checklist

States: `Not started` · `In progress` · `Implemented, unverified` · `Verified` · `Blocked`.
"Verified" means automated tests passed against real PostgreSQL and/or the feature was exercised in the running app (see [TESTING.md](TESTING.md)).

_Last updated: 2026-11-26 — all 10 roadmap phases complete, plus the administrator dashboard._

## Foundation

| Item | State | Evidence |
|---|---|---|
| Phase 1 plan, ER diagram, permission matrix, architecture | Verified | `docs/IMPLEMENTATION_PLAN.md`, `DATABASE.md`, `PERMISSIONS.md`, `ARCHITECTURE.md` |
| Maven build + wrapper, Java 21 target | Verified | `mvnw verify` |
| Compose PostgreSQL 17, health check, named volume, loopback port | Verified | DOC-01 |
| First-run init script (migrator + restricted app role + test DB) | Verified | DOC-01, DB-09 |
| Versioned migrations with checksums; upgrade without volume reset | Verified | DB-06..08, DOC-03 (V001→…→V006 on the dev database), DOC-05 |
| Generated `database/schema.sql` | Verified | `mvnw exec:java@migrate -Dexec.args=schema` |
| Config precedence (env > application.properties > .env > defaults) | Verified | CFG-01..02 |
| Container build/test (`build-test` profile) | Verified | 155 tests (29 unit + 126 integration) passed in the container on Phase 9 code, with Maven offline |
| Docker GUI profile (X11 / WSLg) | Verified | Windows 11 + Docker Desktop + WSLg: real login screen captured from inside the container display (screenshot 90). Linux/X11 variant documented, not run here |
| Startup readiness + connection-failure / outdated-schema screens | Verified | UI-02, UI-03 (screenshots 108, 109);  `StartupView`; success path verified (UI-01) |
| Backup / restore instructions | Verified | Round trip into a scratch database with identical counts; the earlier PowerShell `>` instruction produced corrupt dumps and was replaced (H-02, H-03) |

## Modules

| # | Module | Tier | State | Notes |
|---|---|---|---|---|
| 1 | Authentication and sessions | Essential | Verified | PBKDF2 (600k), student-only registration, provisioning tool, logout cancels tasks |
| 2 | Dashboards | Essential→expanded | Verified | Greeting, stat cards, today, upcoming previews, recent materials, CR duties, professor courses, pending quizzes, weak topics, professor quiz analytics, student attendance with warnings and open-session code box, professor attendance overview, announcements, to-do card (pending feedback, notes to review, open/unresolved doubts, recent feedback), learning streak, badges, study tasks (students) and syllabus completion (professors) — every item listed in the brief for both dashboards is present |
| 3 | Courses and lectures | Essential | Verified | CRUD/archive, enrollment, CR delegation, topics, lecture states, schedule week/upcoming/previous, lecture detail |
| 4 | Study materials | Essential | Verified | Upload (FileChooser), allowlist + size + signature, managed names, download/save-as, open with OS, search/sort/filter, publish |
| 5 | Lecture quizzes | Essential | Verified | Editor (MCQ, True/False, topics, explanations), publish rules, DB-time deadline, autosave + resume, auto-submit at expiry (service and real UI), idempotent and concurrent-safe submit, release policies, review with incorrect-only filter, practice attempts kept separate, professor analytics with accuracy chart, Weak Topic Detector. Question/quiz dialogs verified through services; click-through of the modal dialogs still Planned (QZ-UI-07) |
| 6 | Temporary-code attendance | Essential | Verified | SecureRandom 6-character codes (hash stored, never the code), DB-time expiry with automatic renewal on the professor screen, live present count, wrong-code limit, enrollment and duplicate checks (Java + primary key), one open session per lecture, finalization with ABSENT rows (race-tested against concurrent submits), atomic reasoned corrections with append-only history, student percentages with <75% warnings, course reports. Finalize/correct dialogs not yet clicked through (ATT-UI-04) |
| 7 | Lecture feedback | Essential | Verified | One per student per lecture (Java + UNIQUE), 4 ratings 1–5 + comments, edit while open, professor open/anonymous settings, anonymous identity kept out of report queries and audit log, participation, averages, per-course trend chart |
| 8 | Notes and approval | Essential | Verified | PENDING→APPROVED/REJECTED, resubmission (optional new file, old file removed), professor or delegated CR review, never own, rejection reason required, history, private until approved (list/search/open/download/notifications), unique upvotes, helpful ranking, downloads, professor recommendations |
| 9 | Doubt forum + Doubt Heatmap | Essential | Verified | Course/lecture doubts, replies with professor-answer marking, unique reply upvotes, search (literal wildcards), unresolved/mine filters, resolve by author/professor/delegated moderator, hide/unhide by moderators only, heatmap of unresolved doubts per syllabus topic |
| 10 | Announcements and notifications | Essential | Verified | Categories incl. urgent/room change/cancellation, professor or delegated CR publishing to the course audience, persisted notifications with read/unread and links, dedupe keys, automatic notifications (cancellation, room change, quiz/material published, note decisions, doubt replies/resolution, low attendance), top-bar bell polling every 30 s |
| 11 | Revision mode | Advanced | Verified | Completed lectures with key concepts, released wrong/unanswered questions with correct answers and explanations, practice re-attempts (never official), mark topic revised (once per day) with history |
| 12 | Performance and achievements | Advanced | Verified | Released official quiz scores chart, attendance, current/longest streak (academic time zone), weekly activity chart, weak topics, 5 deterministic badges awarded once; activity derived from records so viewing never inflates it; badges never touch marks |
| 13 | Semester planner | Advanced | Verified | Planned date/hours/notes per topic, reorder within unit, change unit, add topics, status derived from real lectures (completed/scheduled/overdue/planned), syllabus completion %, dashboard card |
| 14 | Resource library | Advanced | Verified | Published materials, PYQs, references and approved notes grouped by course and unit; filters (course, type, unit, recommended, search); same MaterialService access rules; no duplicated files or metadata |
| 15 | Bookmarks and study tracker | Advanced | Verified | Private bookmarks (materials/notes, lectures, doubts) re-checked on every listing, private study tasks with due/overdue and completion, recent activity, revision history |

## UI system

| Item | State |
|---|---|
| BorderPane shell, top bar (brand, scoped search, theme toggle, profile/logout), collapsible sidebar | Verified (screenshots) |
| Notification bell in top bar | Verified (unread count matches PostgreSQL in the UiSnapshots check; screenshot 60) |
| Material Design 3 theme in the Thapar Institute palette, campus photo banners (login, dashboard, courses, lectures, startup), Thapar logo mark ([DESIGN.md](DESIGN.md)) | Verified (screenshots regenerated 2026-11-26) |
| Light + dark theme with tokens; preference saved per user and locally | Verified (screenshots 01, 02, 15) |
| Reusable cards, stat cards, badges, empty/loading/error states, toasts, dialogs | Verified (in use) |
| FXML login with show/hide password and inline validation | Verified |
| Charts | Verified — `BarChart` question accuracy (44) and weekly activity (83), `LineChart` feedback trend (68) and quiz scores (83), doubt heatmap built from regions (63) |
| Keyboard focus rings (focus-visible), Ctrl+B / Ctrl+F shortcuts | Verified: TAB order on login and Ctrl+B (UI-05, UI-07); Ctrl+F and the look of focus rings checked by hand only |

## Handover (Phase 10)

| Item | State | Evidence |
|---|---|---|
| Real dialogs driven end to end with database checks | Verified | 13 dialogs/confirmations (TESTING.md H-04) |
| Dark theme, 980×640 window, keyboard traversal | Verified | screenshots 100–107, UI-06, UI-07 |
| Data survives app exit and PostgreSQL restart | Verified | E2E-01, DOC-04 |
| Demo script | Done | `docs/DEMO_SCRIPT.md` |
| Final viva guide | Done | `docs/VIVA_GUIDE.md` (overview, 15 module sections, whole-project questions) |
| Native FileChooser upload / open with OS app | Not automated | services verified (MAT-01..07); see TESTING.md "Not covered" |

## Administrator dashboard (added after Phase 10)

| Item | State | Evidence |
|---|---|---|
| `ADMIN` role (migration V007), admin-only sidebar, no academic access | Verified | ADM-01, ADM-11, PERMISSIONS.md |
| Dashboard: counts per role, disabled count, quick add, newest accounts, recent sign-ins, account-change log | Verified | ADM-09, screenshots 120, 124 |
| Add Student / CR / Professor / Administrator with generated or typed password | Verified | ADM-02, ADM-03, ADM-UI-02 |
| Search / filter all accounts; edit details | Verified | ADM-09, screenshot 121 |
| Student ↔ CR switch (demotion removes course delegations) | Verified | ADM-04 |
| Reset password, disable / enable (never self, never the last admin, race-safe) | Verified | ADM-05..08, ADM-UI-03/04 |
| First administrator from the command line (`Provision admin`) | Verified | ACCOUNTS_AND_CREDENTIALS.md §5.0 |
