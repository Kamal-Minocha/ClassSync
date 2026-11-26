# ClassSync: Master Prompt for an AI Coding Agent

Use this document as the implementation brief, complete feature list, and development contract for ClassSync. Read the entire document before starting. Follow the approval gate in Section 16 before writing application code.

## 1. Your role and project objective

Act as a careful Java desktop developer and a beginner-friendly teacher. Build **ClassSync**, a college Learning Management System in which every lecture is a complete learning unit covering preparation, teaching materials, attendance, quizzes, feedback, collaborative notes, discussion, and revision.

The finished application must look like a polished modern productivity application while using straightforward Java that a student can understand and explain in a practical examination or viva.

Priorities:

1. Real, persistent functionality and correct permissions.
2. Correct PostgreSQL data design and direct JDBC usage.
3. A polished, responsive JavaFX desktop interface.
4. Simple, understandable architecture.
5. Reproducible Docker setup, meaningful tests, and clear documentation.
6. Beginner-friendly explanations tied to Java syllabus Units 4 and 5.

This is a **desktop application**. All application screens must use JavaFX. Do not build a browser-based frontend.

## 2. Non-negotiable execution rules

### Agent concurrency

- Default to **zero sub-agents**. Perform the work yourself.
- Never have more than **one implementation agent actively working at a time**, including yourself.
- Do not spawn parallel workers, agent teams, or concurrent background coding agents.
- If delegation is supported and necessary, use at most **one sub-agent**, sequentially. While that sub-agent works, supervise or wait; do not perform a second implementation task concurrently. End its work before resuming implementation or delegating another task.
- If the environment cannot enforce that limit, do not delegate.
- These restrictions apply to agents, not ordinary application background tasks, Docker services, Maven compilation, or test execution.

### Scope and working style

- Inspect the existing repository and applicable instructions first. Preserve unrelated user changes.
- Present the initial plan and wait for explicit approval before generating application code, schema implementation, or container configuration.
- After the plan is approved, implement one small, testable module at a time. Continue within the approved scope without repeatedly requesting approval for routine reversible work.
- Do not generate the entire project in one response or produce hundreds of speculative files.
- Compile and validate each milestone before expanding it.
- Maintain a feature checklist with `Not started`, `In progress`, `Implemented, unverified`, `Verified`, or `Blocked` states.
- Never describe mocked screens, placeholder buttons, invented screenshots, or unrun tests as completed functionality.
- Document assumptions and actual blockers. Ask only when a missing decision materially blocks safe progress.
- Do not deploy to public hosting, push code, publish artifacts, or expose services publicly unless explicitly requested. Running the approved local Docker environment is part of implementation.
- Do not delete Docker volumes, reset a database with user data, or remove uploaded files without explicit authorization. Use an isolated disposable test database for destructive tests.

## 3. Strict technology stack

| Area | Required choice |
| --- | --- |
| Language | Java 21 LTS; use Java 17 only if the existing environment requires it and explain the choice |
| Desktop UI | JavaFX / OpenJFX, JavaFX CSS, JavaFX Charts |
| UI organization | FXML when it makes screens easier to maintain; Scene Builder is optional |
| Build | Maven, preferably with Maven Wrapper |
| Database | **PostgreSQL only** |
| Database access | Direct JDBC using the PostgreSQL JDBC driver |
| Database runtime | Docker Compose with a PostgreSQL container and persistent volume |
| Files | Standard Java file APIs and JavaFX FileChooser |
| Passwords | Standard Java security APIs, with salted PBKDF2 password hashes |
| Tests | JUnit and direct JDBC integration tests against an isolated PostgreSQL database |
| Diagrams | Text-based Mermaid diagrams in Markdown documentation |

**PostgreSQL explicitly replaces every MySQL requirement from earlier project descriptions**, including drivers, SQL syntax, configuration, sample data, documentation, and viva explanations. Do not introduce MySQL, MariaDB, MySQL Workbench, or a second database implementation.

Do not use Spring, Spring Boot, Hibernate, JPA, any ORM, React, Angular, Vue, Node.js, Express, PHP, Python backends, Django, Flask, Firebase, Supabase, MongoDB, or REST API frameworks. Do not embed a web application in WebView as a substitute for JavaFX screens.

Avoid unnecessary frameworks, dependency injection containers, microservices, generic repository frameworks, or extra infrastructure. Small libraries for testing or icons are acceptable only when justified and documented.

## 4. Docker and execution requirements

Deliver a reproducible local Docker workflow, not just a mention of Docker in the README.

### Required database setup

- Provide `compose.yaml` with a PostgreSQL service, a health check, and a named persistent data volume.
- Pin an appropriate supported PostgreSQL major version; do not use `latest`. Verify compatibility when implementing and record the chosen versions.
- Bind the database port to loopback by default. Keep the database off the public network.
- Supply `.env.example` with clearly marked placeholders. Ignore the real `.env` and other local secrets in Git.
- Keep runtime credentials separate from checked-in code. Prefer a restricted application database user and a separate bootstrap or migration owner.
- Use a host JDBC URL such as `jdbc:postgresql://localhost:5432/classsync`; a containerized application uses the Compose service hostname, such as `postgres`, instead of `localhost`.
- Include startup readiness handling and a useful connection failure screen or message.
- Store timestamps representing instants in PostgreSQL `TIMESTAMPTZ`. Use a configured academic timezone for schedules, display, deadlines, and daily activity calculations.
- Explain that first-run container initialization scripts run only against an empty database volume. Provide an explicit, versioned SQL migration workflow for existing installations.
- Never require deleting a volume to apply a normal schema update.
- Include backup and restore instructions for PostgreSQL and uploaded files together.

### Required application execution paths

1. **Everyday desktop mode:** PostgreSQL runs in Docker; JavaFX runs on the host with Maven or a packaged launch command. This must be convenient on Windows.
2. **Container build/test mode:** provide a Dockerfile and documented commands that compile the Java project and run the appropriate automated tests. Integration tests must target a dedicated PostgreSQL test database or service.
3. **Docker desktop GUI profile:** provide an opt-in container profile for the JavaFX application with at least one documented supported graphical environment, such as Linux X11 or WSLg. Install the native JavaFX dependencies needed by that image, configure the display connection, and mount a persistent uploads directory or volume. Document platform prerequisites and limitations. The UI must remain a native JavaFX application; do not create a browser frontend to make container execution easier.

The default `docker compose up -d postgres` path must not require a display server. Docker does not provide a desktop display automatically. Test the GUI profile in a supported display environment when available, and report precisely if GUI execution could not be verified in the current environment. Do not claim a successful container GUI launch from a successful image build alone.

Use a multi-stage Docker build where helpful. Provide `.dockerignore`. Ensure the runtime has the correct Java version, OpenJFX modules, and OS/architecture-compatible native libraries. Do not bake passwords or real uploads into an image.

## 5. Learning experience

Organize the application around these three stages:

| Stage | Student experience |
| --- | --- |
| Before a lecture | Upcoming topic, learning objectives, preparation instructions, recommended resources, and planned teaching content |
| During a lecture | Lecture materials, active quizzes, temporary-code attendance, and academic doubts |
| After a lecture | Short quizzes, feedback, approved notes, incorrect-answer review, revision, and progress tracking |

Upcoming lecture previews are a signature feature and belong in the initial usable version.

## 6. Roles, permissions, and registration

Implement exactly three application roles: **Student**, **Professor**, and **Class Representative (CR)**. Do not add a web administration system or an administrator role without approval.

Enforce authorization in service methods using straightforward Java conditions and database lookups. Hiding buttons is only a UI convenience. Check course membership, course assignment, ownership, record state, and any delegated permission on every relevant read or mutation.

### Student

Students can register with valid college credentials, log in, see their enrolled courses and timetable, view previous/upcoming lectures, access published resources, download materials, attempt quizzes, review released results, mark attendance, give feedback, submit notes, track approval status, access approved notes, upvote contributions, ask and answer doubts, see announcements and notifications, search resources, bookmark items, and track revision and learning activity.

Registration must create a Student account only. Validate the configured college email format and unique enrollment number. Do not imply email ownership verification or college single sign-on exists unless actually implemented. Professor and CR accounts are created through documented local provisioning or seed utilities. Never let registration choose a privileged role.

### Professor

Professors manage their own assigned courses, enrollments, lectures, materials, quizzes, attendance sessions, feedback settings, note approvals, discussions, announcements, semester plans, and course analytics. They can review students, finalize attendance, correct attendance with a reason, and delegate specific course permissions to a CR.

For the simplest initial model, allow a professor to create a course assigned to themselves. Only its assigned professor may edit/archive it or manage enrollment and CR delegation. Explain any later expansion to multiple professors.

### Class Representative

A CR retains student learning features for courses in which they are enrolled. CR staff privileges are **course-scoped**, not global.

CRs can view assigned course schedules and upload course materials. Additional actions require explicit professor delegation: updating lecture information, reviewing notes, publishing announcements, organizing resources, or moderating discussions.

CRs must never modify official quiz marks, finalize or correct official attendance, change user roles, approve their own notes, or grant themselves permissions. Revocation of a permission must take effect on the next service operation.

Document a role/action permission matrix, including default CR permissions and delegated permissions.

## 7. Complete feature list

**Essential** features establish a useful first release. **Advanced** features are scheduled later, but remain part of the full requested project. Do not silently omit them or claim the whole project is finished after the first release.

### Module 1: Authentication and sessions — Essential

- Attractive login screen with college email or enrollment number, password, show/hide password, validation, useful errors, and role navigation.
- Student registration, login, logout, and an in-memory authenticated session.
- Salted password hashing, duplicate account prevention, and sample accounts for all three roles.
- Clear session state on logout, cancel user-specific tasks, and prevent access through a previous screen or cached action.

### Module 2: Personalized dashboards — Essential, then expanded

- Student: greeting, today's timetable, upcoming lectures/topics, overall and subject attendance, pending quizzes, announcements, recent materials, pending feedback, learning progress, streak, and weak topics.
- Professor: today's lectures, assigned courses, enrolled student counts, attendance overview, quiz shortcut, quiz analytics, pending note approvals, recent feedback, unresolved doubts, and upcoming schedule.
- CR: assigned courses, daily lecture updates, material upload shortcut, permitted approvals, announcements, and upcoming lectures.
- Use clickable summary cards, progress bars, and charts backed by persisted data. Add dashboard sections as their underlying modules become available; do not fill missing modules with fake metrics.

### Module 3: Courses and lectures — Essential

- Course fields: name, unique code, department, semester, professor, description, and enrolled students.
- Lecture fields: course, lecture number, date/start/end time, unit number, topic, description, objectives, preparation instructions, and status.
- Course and lecture create/read/update/archive flows; safe deletion only when no dependent academic records would be lost.
- Enrollment management with duplicate prevention.
- Daily timetable, previous and upcoming lectures, published upcoming topics, preparation materials, and lecture detail pages.
- Define explicit draft/published/cancelled/completed states and enforce visibility.

### Module 4: Study material management — Essential

- Professor and permitted CR uploads using FileChooser.
- Support PDF, PPT/PPTX, DOC/DOCX, JPG/JPEG, and PNG.
- Title, description, course, optional lecture, unit, uploader, publication state, and upload history.
- Download/save-as, local opening through a supported OS association, search, sorting, and filtering.
- Store file bytes in a managed uploads directory and metadata/relative paths in PostgreSQL.
- Enforce course membership, file size/type rules, and publication permissions.

### Module 5: Lecture quizzes — Essential; detailed analytics later

- Professor quiz editor: lecture, title, instructions, opening/closing times, duration, marks, publication state, and attempt/review rules.
- MCQ questions with one correct choice and True/False questions, answer options, topic association, and positive marks per question.
- Student attempt screen with question navigation, answer saving, countdown, submit confirmation, and automatic submission at expiry.
- Automatic evaluation, released scores, incorrect-answer review, and permitted revision attempts.
- Professor submissions view, average/highest/lowest score, participation, question accuracy, and frequently selected wrong answers.
- **Weak Topic Detector:** SQL aggregates and Java thresholds, with no AI or machine learning. Document minimum sample size, correctness threshold, and behavior when data is insufficient.

### Module 6: Temporary-code attendance — Essential

- Professor selects a lecture, starts a session, displays a random short-lived code, reviews submissions, and finalizes attendance.
- Validate code, session state, database time/expiry, and enrollment before accepting a submission.
- Prevent duplicate submissions with database constraints as well as Java checks.
- Attendance history, finalized reports, course percentage, low-attendance warnings, and audit trail.
- Professor-only corrections after finalization require a reason and preserve before/after values.
- Keep professor verification in the workflow. Temporary codes discourage casual misuse; do not claim they eliminate proxies.

### Module 7: Lecture feedback — Essential

- One feedback submission per eligible student per lecture, with a defined edit policy.
- Ratings for understanding, clarity, pace, and usefulness; text for difficult concepts and suggestions.
- Professor lecture averages, participation, suggestions, difficult concepts, and trends.
- Professor-configurable anonymous feedback. Hide student identity in anonymous reports and exclude identity from report exports and routine logs. Explain the limits of application-level anonymity in the documentation.

### Module 8: Student notes and approval — Essential

- Upload handwritten or digital notes with course, lecture, title, and description.
- State machine: `PENDING` → `APPROVED` or `REJECTED`; resubmission returns content to review.
- Professor or delegated CR review, rejection comments, approval history, and uploader status/history.
- Only approved notes are visible to other enrolled students. Authors may see their own pending/rejected submissions; authorized reviewers may inspect them.
- Prevent self-approval, including when the author is a CR or professor.
- Unique upvotes, helpful-note ranking, download counts, and professor recommendations.

### Module 9: Doubt discussion forum — Essential; heatmap later

- Course/lecture doubts, replies, professor answers, reply upvotes, search, and resolved/unresolved state.
- The doubt author or assigned professor may resolve a doubt; delegated moderation permissions must be explicit.
- **Doubt Heatmap:** show topics with the most unresolved doubts using SQL counts and JavaFX visualization.
- Enforce enrollment and course-scoped moderation.

### Module 10: Announcements and notifications — Essential

- General and urgent notices, cancellations, room changes, quiz announcements, material updates, and attendance warnings.
- Professor and delegated CR publishing with correct course audiences.
- Persist in-app notifications with unread/read state and links to the relevant record.
- Use a simple refresh strategy and avoid duplicate notifications. Email, SMS, mobile push, and external messaging are outside scope.

### Module 11: Lecture revision mode — Advanced

- Browse completed lectures, old resources, important concepts, and released incorrect-answer explanations.
- Reattempt quizzes only when allowed. Keep practice results separate from official academic results.
- Mark topics revised and retain revision history.

### Module 12: Academic performance and achievements — Advanced

- Quiz scores, attendance trends, learning progress, weekly activity, weak topics, and learning streaks.
- Deterministic badges: First Quiz, Perfect Score, Five-Day Learning Streak, Helpful Contributor, and Notes Champion.
- Define the event/threshold for each badge, the daily streak timezone, and what counts as meaningful activity. Reopening a screen must not inflate activity.
- Achievements must never change official grades.

### Module 13: Professor semester planner — Advanced

- Semester plan, topic/unit sequencing, planned dates, completed and pending topics, rescheduling, and syllabus completion.
- Keep planned topics linked to actual lectures and upcoming-topic previews.

### Module 14: Central resource library — Advanced

- Course/subject and unit organization, previous-year questions, references, approved notes, professor PDFs, and recommended materials.
- Search and filter with consistent access control.
- Reuse material records instead of duplicating file bytes or metadata into a separate storage system.

### Module 15: Bookmarks and personal study tracker — Advanced

- Bookmark resources/notes, personal revision tasks, task completion, recent activity, and revision history.
- Keep each student's saved items and personal tasks private.

## 8. JavaFX visual and interaction requirements

Create an original premium visual system inspired by modern productivity tools and university dashboards. Use deep navy, indigo, violet, teal/cyan highlights, off-white surfaces, and a coherent dark theme. Avoid default-looking JavaFX controls.

- Define reusable CSS tokens/styles for typography, spacing, radii, colors, focus, hover, disabled states, and semantic status colors.
- Use a `BorderPane` application shell: top navigation, collapsible sidebar, and dynamic main content.
- Top navigation: ClassSync identity, scoped search, notifications, profile/logout, and theme toggle.
- Role-relevant sidebar: Dashboard, My Courses, Lecture Schedule, Study Materials, Quizzes, Attendance, Feedback, Notes, Discussions, Resource Library, and Settings. Add planner, performance, and revision navigation where relevant.
- Build reusable cards, form rows, badges, empty states, loading indicators, dialogs, and success/error notices.
- Use `HBox`, `VBox`, `GridPane`, `BorderPane`, `StackPane`, `ScrollPane`, `TableView`, `ListView`, and `TabPane` where appropriate.
- Demonstrate `Label`, `TextField`, `PasswordField`, `Button`, `TextArea`, `CheckBox`, `ComboBox`, `ProgressBar`, `Alert`, and `Dialog`.
- Use JavaFX Charts for meaningful data; handle zero/insufficient data truthfully.
- Include active navigation, sorting/filtering, keyboard navigation, clear focus rings, accessible contrast, readable labels, window resizing, and scrolling on smaller displays.
- Use restrained hover effects, short transitions, and optional progress animations. Timers and workflows must stay correct when animations are disabled.
- Long database/file work belongs in `Task`, `Service`, or a small managed executor. UI updates belong on the JavaFX Application Thread.
- Cancel or safely finish background work on logout and application shutdown. Prevent stale results from updating a new user's session.
- Persist theme preference simply; do not build a complicated settings system.

## 9. Simple Java architecture and project structure

Use this flow:

`JavaFX View → Controller → Service → DAO → JDBC → PostgreSQL`

- **Model:** data classes and small enums.
- **DAO:** SQL, PreparedStatement bindings, ResultSet mapping, and persistence.
- **Service:** permissions, validation, business rules, and transaction boundaries.
- **Controller:** UI events, view state, and navigation.
- **View:** FXML or straightforward JavaFX layouts.
- **Config:** external configuration and JDBC connection creation.
- **Util:** focused helpers for passwords, files, dates, and validation.

Do not put SQL in controllers or business rules in CSS/view files. Avoid a giant `Main.java` and avoid creating interfaces/factories for every small class.

Suggested structure; create files only as needed:

```text
ClassSync/
  CLASSSYNC_MASTER_PROMPT.md
  pom.xml
  mvnw
  mvnw.cmd
  .mvn/
  compose.yaml
  Dockerfile
  .dockerignore
  .env.example
  .gitignore
  README.md
  database/
    schema.sql
    sample_data.sql
    migrations/
  docs/
    IMPLEMENTATION_PLAN.md
    FEATURE_CHECKLIST.md
    PERMISSIONS.md
    DATABASE.md
    ARCHITECTURE.md
    DOCKER.md
    TESTING.md
    VIVA_GUIDE.md
    DEMO_SCRIPT.md
    screenshots/
  uploads/
    materials/
    notes/
    announcements/
  src/
    main/
      java/com/classsync/
        Main.java
        config/
        model/
        dao/
        service/
        controller/
        view/
        util/
      resources/
        fxml/
        css/
        images/
        application.properties.example
    test/
      java/com/classsync/
      resources/
```

Explain a complete example such as `LoginController → AuthenticationService → UserDAO → PostgreSQL`, including which layer handles each failure.

Demonstrate the JavaFX lifecycle with `init()`, `start(Stage)`, and `stop()`. Respect lifecycle/thread restrictions: construct and manipulate the live UI on the JavaFX thread, and release executors/connections in shutdown handling.

## 10. PostgreSQL data design

Create a normalized relational model. Start with a complete conceptual design, then implement tables as modules need them. Supply a current full `schema.sql`, versioned upgrade SQL, and repeatable demo-data instructions.

Required logical entities, with justified consolidation allowed:

| Group | Tables / entities |
| --- | --- |
| Accounts | `users`, `students`, `professors` |
| Courses | `courses`, `enrollments`, `course_staff`, `lectures`, `topics` |
| Resources | `materials`, `material_approvals`, `notes_votes` |
| Quizzes | `quizzes`, `questions`, `question_options`, `quiz_attempts`, `answers` |
| Attendance | `attendance_sessions`, `attendance` |
| Feedback | `feedback` |
| Communication | `announcements`, `notifications`, `discussions`, `discussion_replies`, `reply_votes` |
| Progress | `achievements`, `student_achievements`, `teaching_plans`, `bookmarks`, `revision_history`, `study_tasks`, `activity_events` |
| Accountability | `audit_logs`, schema version/migration tracking |

Model notes as a material kind with approval state if that keeps the design simpler. Keep CR permissions in `course_staff` with clear fields or a small related table. Explain how CR student identity and course delegation relate.

Database requirements:

- Primary keys, foreign keys, useful indexes, `NOT NULL`, `CHECK`, and unique constraints.
- Unique email/enrollment, enrollment per student/course, attendance per student/lecture, and one vote per voter/item.
- Constrain quiz attempt numbering, answer uniqueness per attempt/question, feedback per student/lecture, bookmarks, and badge awards.
- Prefer real foreign keys for bookmarks rather than an unchecked arbitrary `entity_type + entity_id` pair.
- Preserve course/lecture consistency in related records through keys/constraints where feasible and transactional service validation where necessary.
- Use `question_options` instead of ambiguous names such as `options`.
- Use identity columns or another clear PostgreSQL-native identifier strategy. Do not copy MySQL `AUTO_INCREMENT`, backticks, or engine clauses.
- Represent official versus practice attempts explicitly. Record the scoring/review policy.
- Derive quiz total marks from questions or validate the cached total transactionally.
- Decide foreign-key delete behavior deliberately. Archive records that already have attendance, scores, or other academic history.
- Index common searches and foreign-key lookup/filter columns. Avoid premature full-text infrastructure.
- Define rating bounds, positive marks, valid date ranges, and relevant status constraints.
- Include a Mermaid ER diagram and plain-language relationships.

Demo data must be realistic and deterministic: all roles, multiple courses, enrollments, past/current/upcoming lectures, approved/pending/rejected notes, quizzes, attendance, feedback, discussions, and announcements. Generate usable relative schedules or document the demo date so quizzes and sessions are not accidentally unusable. Label demonstration credentials clearly and store only password hashes in PostgreSQL. Keep demo data opt-in and separate from production/user data.

## 11. JDBC, transactions, and correctness

- Use `PreparedStatement` for every user-controlled value. Parameterize search terms and pagination. Whitelist dynamic sort columns/directions instead of concatenating arbitrary input.
- Demonstrate `ResultSet`, `INSERT`, `SELECT`, `UPDATE`, and `DELETE` in a small, safe first DAO example.
- Use try-with-resources for connections, statements, and result sets.
- Do not share a single mutable JDBC connection across JavaFX background tasks.
- For multi-table operations, let the service open a connection, disable auto-commit, pass that same connection to participating DAO methods, commit on success, and roll back on failure. Do not allow nested DAO calls to silently open unrelated connections inside a transaction.
- Use transactions for quiz submission/scoring, attendance finalization/correction, notes review/history, enrollment-related changes, and notification writes that must accompany an action.
- Use row locking or conditional state updates where needed to prevent races. Database uniqueness must be the final defense against duplicate concurrent submissions.
- Translate expected constraint failures into understandable UI messages. Log diagnostic details without exposing passwords, credentials, anonymous feedback identities, or correct quiz answers.

### Quiz timing and result integrity

- Persist attempt start, deadline, status, saved answers, and submission time.
- Determine the deadline from the permitted duration and quiz closing time using an authoritative database time check. A JavaFX countdown displays the deadline; it must not be the sole enforcement mechanism.
- Save answers incrementally and implement expiry handling for reopening/restarting the application. Do not rely on a countdown thread surviving a crash.
- Submission must be idempotent: repeated clicks, retries, and expiry handling must not score twice.
- Prevent edits after submission/expiry. Freeze published questions after attempts begin, or implement a clearly justified versioning policy.
- Hide correct answers until the review policy permits release. Student-facing queries/models must not include answer keys before release.

### Attendance correctness

- Generate codes using `SecureRandom`, with sufficient entropy and short configurable expiry.
- Do not show or persist codes in general logs. Bound repeated invalid attempts in the application and document the limitation of client-side limits.
- Prevent overlapping active sessions for the same lecture unless the design explicitly handles them.
- Define which finalized, non-cancelled lectures count toward attendance and how absences are recorded.
- Finalization must prevent later ordinary submissions. Concurrent finalization and submission must yield one consistent result.
- Manual correction must save the actor, time, reason, old value, and new value in the same transaction.

### Analytics definitions

Document denominators and missing-data behavior for attendance, quiz participation, performance, syllabus completion, weak topics, and feedback averages. Never display misleading percentages or divide by zero. Separate official results from revision practice in academic analytics.

## 12. Security, files, and privacy

- Use `PBKDF2WithHmacSHA256`, a fresh random salt per password, a documented suitable work factor, and a stored format that includes algorithm parameters. Compare derived hashes with a constant-time comparison. Do not use a plain SHA hash as password storage.
- Load database configuration from environment variables or an ignored local properties file. Provide examples and explain precedence. Java host execution must not assume Compose automatically injects `.env` values into the host process.
- Do not display JDBC credentials in dialogs or commit secrets.
- Treat every selected record ID as untrusted: recheck access through the service, including downloads, reviews, exports, and analytics.
- Validate and limit free-text fields; avoid unbounded database loads and oversized attachments.
- Configure a sensible maximum upload size, an allowlist of extensions, and basic content/signature checks where practical.
- Generate managed filenames instead of trusting original names. Resolve and normalize paths under the upload root, and reject traversal and paths that escape it, including unsafe symlink targets.
- Store relative paths only. Preserve a safe display filename separately. Never overwrite unrelated files.
- Handle database/file failure together: clean up failed uploads and prevent committed metadata from referencing missing files. Document that filesystem operations do not participate directly in JDBC transactions.
- Permission-check opening and downloading just as strictly as listing. Do not expose another student's pending/rejected notes through search, bookmarks, direct paths in the UI, or notifications.
- Prevent duplicate votes and unauthorized/self note approvals at the service and database layers where appropriate.
- Keep anonymous feedback identities out of professor-facing models and queries. Retain only what is needed for eligibility/duplicate enforcement; document who can technically access database records.
- Explain the deployment trust boundary: shared-database desktop clients and local files suit a controlled college demonstration. Service checks in a distributed desktop binary do not provide the same isolation as a server boundary against a malicious modified client. Do not add a forbidden backend or claim enterprise security.
- Document local file storage as a single-machine/shared-storage demonstration model. A shared PostgreSQL database alone does not distribute files between computers. If demonstrating multiple clients, configure the same managed shared upload root and explain its access assumptions.

## 13. Phased implementation roadmap

For each phase, describe scope, files/tables, permissions, tests, and the visible result. Keep the application runnable after each milestone.

| Phase | Work | Exit criteria |
| --- | --- | --- |
| 1. Plan and design | Repository inspection, requirements split, simplest solution per feature, architecture, ER diagram, permission matrix, UI system, Docker strategy, roadmap | Written plan presented; explicit approval received before implementation |
| 2. PostgreSQL and JDBC foundation | Maven setup, Compose database, secrets examples, schema/migrations, connection config, first DAO CRUD and transaction example, container build/test skeleton | Healthy database; real JDBC CRUD and rollback verified; compile succeeds |
| 3. JavaFX foundation | Lifecycle, Stage/Scene, BorderPane shell, navigation, top bar, sidebar, reusable components, light/dark CSS, login view | Application launches; resizes cleanly; representative controls and navigation work |
| 4. Authentication and role shells | Passwords, student registration, login/logout, provisioning, service authorization, role dashboards | All three roles can sign in; invalid credentials and forbidden actions are rejected |
| 5. Smallest usable learning loop | Course/enrollment management, lecture CRUD/archive, upcoming topics, preparation material, uploads/downloads, schedule, minimal dashboard queries | Professor publishes a lecture and resource; enrolled student sees preparation and downloads it; data survives restart |
| 6. Quizzes | Editor, questions, publication, timed attempt, autosave, scoring, review, analytics, weak topics | Real student attempt persists and scores correctly; expiry/retry/permissions tested |
| 7. Attendance | Sessions, temporary codes, expiry, enrollment/duplicate checks, finalization, correction, audit, reports | End-to-end professor/student workflow and concurrent state boundaries verified |
| 8. Collaboration | Feedback/anonymity, notes review, doubts/replies, announcements, notifications | Full workflows work for all roles; delegated permissions and private notes tested |
| 9. Advanced learning tools | Revision, performance, planner, library, bookmarks/tasks, activity/streaks, achievements, heatmap | Every advanced feature has real persisted behavior and defined calculations |
| 10. Final integration and handover | Regression checks, UI polish, Docker GUI profile verification, setup reproducibility, diagrams, screenshots, viva guide, demonstration | Full checklist reconciled; actual test results and platform limitations documented |

Do not defer all tests or all explanations to Phase 10. Add them with each module. A phase may contain several small working increments.

## 14. Testing and verification

Use focused service tests for rules and JDBC integration tests against **real PostgreSQL** for persistence/constraints/transactions. Do not substitute SQLite, H2, or mocked SQL behavior for PostgreSQL integration verification.

Essential checks:

- Authentication: registration validation, duplicate accounts, correct/incorrect passwords, role escalation prevention, logout, and stale-task/session handling.
- Authorization: cross-course access, revoked CR grants, enrollment checks, forbidden direct service calls, and ownership checks.
- Courses/lectures: create/update/archive, schedule ranges, publish previews, and preservation of academic history.
- Files: upload/download, search, unsupported type, oversized file, path traversal, unauthorized access, and file/database failure cleanup.
- Quizzes: question validation, correct grading, answer isolation, timer expiry, closing-time boundary, saved answers/restart, idempotent submission, concurrent submission, and practice/official separation.
- Attendance: valid/invalid/expired code, unenrolled student, duplicates, concurrent finalization, finalized state, percentage calculation, and reasoned correction/audit atomicity.
- Feedback: one-per-lecture enforcement, rating bounds, eligibility, aggregate accuracy, and anonymous identity protection.
- Notes: upload, pending visibility, approve/reject/history, required rejection reason, self-approval prevention, revoked delegation, and duplicate votes.
- Discussions/announcements: post/reply/resolve, course visibility, authorized moderation, notification audience, and no duplicate notifications on retries.
- Progress: weak-topic thresholds, insufficient data, timezone-sensitive streaks, unique badges, bookmarks, revision tasks, and planner totals.
- Docker: clean initialization, health readiness, host/container JDBC addressing, schema upgrade without volume removal, container build/tests, and persistence after restart.
- Desktop: real launch, all role workflows, both themes, resize/scroll, keyboard focus, actionable buttons, empty/loading/error states, and responsive UI during IO.

Use an injected clock or equivalent simple abstraction for timing tests where helpful. Prefer meaningful tests of behavior over tests that merely repeat implementation details. Never run destructive tests against the user's normal database or uploads.

Record commands, outcomes, failed checks, and environment limits. Capture actual screenshots only after launching the application; do not substitute mockups. If graphical verification is unavailable, finish other checks and clearly mark desktop verification as pending.

## 15. Beginner explanations and viva preparation

For every completed module, add a section headed **WHAT I NEED TO KNOW FOR MY VIVA** to the viva guide, containing:

1. What the module does and its end-to-end workflow.
2. Java concepts used.
3. JavaFX layouts, controls, events, and background tasks used.
4. PostgreSQL tables and relationships involved.
5. SQL operations and transaction boundaries.
6. How the JavaFX interface reaches PostgreSQL through controllers, services, DAOs, and JDBC.
7. Important classes/methods and their responsibilities.
8. Five likely viva questions with short, correct answers.

Explicitly map the project to the syllabus:

| Unit | Evidence in ClassSync |
| --- | --- |
| Unit 4: JDBC and database connectivity | JDBC architecture, PostgreSQL connection/driver, CRUD, PreparedStatement, ResultSet, transactions, rollback, try-with-resources, DAO pattern, relational constraints |
| Unit 5: JavaFX | Application lifecycle, Stage, Scene, scene graph, required layouts/controls, event handlers, validation, alerts/dialogs, CSS, navigation, charts, responsive background work |

Explain that PostgreSQL is the requested database substitution for the syllabus's MySQL example; JDBC concepts remain demonstrated, but do not claim the project demonstrates a MySQL connection.

At project completion, include the complete project explanation, Java/JDBC/JavaFX concept summaries, database design, important SQL queries, role access, common viva questions, and a five-minute demonstration script with reliable sample accounts and reset-free steps.

## 16. Your first response and approval gate

**Before writing application code, provide the implementation plan first and wait for my explicit approval.** Planning Markdown and diagrams may be prepared during this step; do not generate the runnable application, SQL implementation, or Docker configuration yet.

The first response must include:

1. Your understanding of ClassSync and the before/during/after lecture experience.
2. Confirmation of JavaFX + direct JDBC + PostgreSQL + Docker, with prohibited technologies excluded.
3. Confirmation that you will use zero sub-agents by default and at most one implementation agent working at any moment.
4. Essential versus advanced features, with the simplest practical implementation for every module.
5. Proposed layers and a Mermaid application architecture diagram.
6. A Mermaid ER diagram, table responsibilities, important constraints, and relationships.
7. Student/Professor/CR permission matrix and course-scoped CR delegation.
8. Maven structure and dependency/version choices, verified when implementation begins.
9. UI theme, navigation, representative screen layouts, and JavaFX control choices.
10. Docker database, persistent storage, migration, build/test, and desktop GUI execution strategy, including Windows expectations.
11. Unit 4/Unit 5 mapping and the PostgreSQL substitution explanation.
12. The phased roadmap, acceptance criteria, and the smallest working version to build first.
13. Meaningful tests, key assumptions, and any real blocking decisions.

Finish by asking for approval of that concrete plan. Do not begin implementation until approval arrives. Once approved, work through the roadmap sequentially and keep the feature checklist current.

## 17. Final deliverables and definition of done

Deliver:

- Complete Java source, JavaFX views, CSS, images/icons with attribution where necessary, and FXML if used.
- Maven configuration/wrapper and documented dependency versions.
- PostgreSQL schema, migrations, realistic sample data, and safe account-provisioning instructions.
- JDBC configuration examples and setup guide without real secrets.
- Compose database service, persistent volumes, Dockerfile, build/test workflow, and optional desktop GUI profile with prerequisites.
- README with prerequisites, exact setup/launch/test commands for Windows and the supported Docker GUI environment, troubleshooting, backup/restore, and upgrade instructions.
- ER and application architecture diagrams.
- Feature checklist and permission matrix.
- Automated tests, manual UI checklist, actual test results, and documented verification limits.
- Actual application screenshots for representative screens and all three roles.
- Beginner-friendly viva guide and five-minute demonstration script.

The project is complete only when all requested modules have real working behavior, database changes survive restart, permissions are enforced in services, relevant tests pass, and another person can follow the documented setup. Report any unverified platform or GUI path precisely. Do not label a partially implemented project complete.

**Build ClassSync step by step. Keep the design polished, the Java understandable, the data correct, and every completion claim supported by evidence.**
