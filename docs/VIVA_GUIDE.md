# ClassSync — Viva Guide

Beginner-friendly explanations of how ClassSync works: first a whole-system overview you can give in two minutes,
then one section per module (each with a "WHAT I NEED TO KNOW FOR MY VIVA" list), and finally the questions
examiners most often ask about the whole project. A live walkthrough is in [DEMO_SCRIPT.md](DEMO_SCRIPT.md).

## The project in two minutes

1. **What it is.** A JavaFX desktop Learning Management System where every lecture is a complete learning unit:
   students prepare *before* (preview, objectives, preparation), take part *during* (materials, quizzes,
   code attendance, doubts) and revise *after* (feedback, approved notes, mistake review, practice).
2. **Who uses it.** Three roles — Student, Professor, Class Representative (CR). A CR is a student who can be given
   specific powers in one course by that course's professor (review notes, publish announcements, update
   lectures, organise resources, moderate discussions). Marks, attendance finalisation and quiz management are
   never delegable.
3. **How it is built.** View (JavaFX pages, FXML login) → Controller → **Service** (rules, permissions,
   transactions) → **DAO** (SQL with `PreparedStatement`) → JDBC → PostgreSQL 17 in Docker. One connection per
   unit of work; multi-step actions use one transaction (`Tx.inTransaction`).
4. **How correctness is protected.** Every permission is checked in the service with a fresh database lookup;
   database constraints (primary keys, unique and partial unique indexes, composite foreign keys, CHECKs) are the
   last line of defence; time-sensitive rules (quiz deadlines, attendance code expiry) use database time.
5. **How it is tested.** 155 automated tests (29 unit + 126 integration) against a real, disposable PostgreSQL
   test database, plus a tool that drives the real JavaFX window and checks results in the database.

## Syllabus mapping

| Unit | Evidence in ClassSync |
|---|---|
| **Unit 4: JDBC and database connectivity** | PostgreSQL JDBC driver (`org.postgresql`), `DriverManager.getConnection` in `config/Database`, `PreparedStatement` everywhere, `ResultSet` mapping in every DAO, generated keys, `setAutoCommit(false)` / `commit` / `rollback` in `config/Tx`, try-with-resources, DAO pattern (`dao/*Dao`), constraints (PK, FK, UNIQUE, CHECK), SQLSTATE error translation (`service/Errors`) |
| **Unit 5: JavaFX** | `Application` lifecycle `init/start/stop` (`Main`), `Stage`, `Scene`, scene graph, layouts (`BorderPane` shell, `HBox`, `VBox`, `GridPane`, `StackPane`, `FlowPane`, `ScrollPane`, `TabPane`), controls (`Label`, `TextField`, `PasswordField`, `Button`, `TextArea`, `CheckBox`, `ComboBox`, `DatePicker`, `TableView`, `ProgressIndicator`), `Alert` and `Dialog` (`view/Dialogs`), FXML + controller (`login.fxml`, `LoginController`), CSS themes, event handlers, background `Task`s (`Background`) |

**PostgreSQL instead of MySQL:** the syllabus uses MySQL as its example database. This project was asked to use PostgreSQL. Every JDBC concept is identical (driver → `Connection` → `PreparedStatement` → `ResultSet`, transactions). Only the driver jar, the URL (`jdbc:postgresql://…`) and a few SQL details (identity columns instead of `AUTO_INCREMENT`, `TIMESTAMPTZ`) differ. ClassSync does **not** demonstrate a MySQL connection.

---

## Module: Database foundation (JDBC, migrations, Docker)

### WHAT I NEED TO KNOW FOR MY VIVA

1. **What it does.** PostgreSQL runs in a Docker container. The migration tool creates the tables from numbered SQL files. Every part of the app gets short-lived JDBC connections from `Database.open()`.
2. **Java concepts.** Classes and records, static factory methods, checked exceptions (`SQLException`), try-with-resources, functional interfaces (`Tx.SqlWork`) and lambdas.
3. **JavaFX.** None directly; the startup screen uses the result of a background database check.
4. **Tables.** `schema_migrations(version, checksum)` records which migration files ran.
5. **SQL and transactions.** Each migration runs inside one transaction: if any statement fails, the whole file is rolled back and not recorded.
6. **Path to PostgreSQL.** `Migrate.main → Database.open() → DriverManager → PostgreSQL`.
7. **Important classes.** `AppConfig` (settings precedence), `Database` (connections), `Tx` (commit/rollback helper), `Migrate` (versioned migrations), `SchemaVersion` (expected version check at startup).
8. **Likely questions.**
   * *Why not one shared Connection?* A JDBC `Connection` is not safe to share between threads; each task opens its own and closes it with try-with-resources.
   * *What does `setAutoCommit(false)` do?* Statements are grouped into one transaction until `commit()`; `rollback()` undoes them all.
   * *Why two database users?* The migration owner can change tables; the app user can only read/write rows, so a bug in the app cannot drop tables.
   * *Why do init scripts only run once?* The PostgreSQL image runs `/docker-entrypoint-initdb.d` only for an empty data volume; later changes go through migrations.
   * *What is a checksum used for here?* To detect someone editing a migration that was already applied.

## Module 1: Authentication and sessions

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** Login screen (FXML) → `LoginController.onSignIn` → background `Task` → `AuthService.login` → `UserDao.findCredentialsByEmail/ByEnrollmentNo` → `PasswordHasher.verify` → `Session.login(user)` → `AppShell` builds the role-specific sidebar and dashboard. Registration creates a **Student** only; professors and CRs come from the `Provision` tool.
2. **Java concepts.** `char[]` passwords that are wiped after use, `SecureRandom` salts, `MessageDigest.isEqual` constant-time comparison, custom exception hierarchy (`AppException` and subclasses), `volatile` fields and `synchronized` methods in `Session`.
3. **JavaFX.** `FXMLLoader` with a controller factory, `@FXML` field injection and `initialize()`, `PasswordField` + `TextField` with a bidirectional binding for show/hide password, `CheckBox`, `ComboBox`, `Hyperlink`, `Task` with `setOnSucceeded/setOnFailed`.
4. **Tables.** `users` (email, role, password hash), `students` (enrollment number), `professors`, `audit_logs`.
5. **SQL.** `SELECT … WHERE lower(email) = lower(?)`; registration inserts `users` + `students` in **one transaction**; the unique index is the final defence against two simultaneous registrations (SQLSTATE 23505 → friendly message).
6. **Layers.** View (`login.fxml`) → Controller (`LoginController`) → Service (`AuthService`) → DAO (`UserDao`) → JDBC → PostgreSQL.
7. **Key methods.** `AuthService.login`, `registerStudent`, `provisionProfessor`, `provisionLearner`; `PasswordHasher.hash/verify`; `Session.login/logout/generation`; `AppShell.onSignedIn/logout`.
8. **Likely questions.**
   * *How are passwords stored?* `pbkdf2_sha256$600000$<salt>$<hash>` — PBKDF2-HMAC-SHA256 with a random 16-byte salt per password; never plain text.
   * *Why the same error for unknown user and wrong password?* So an attacker cannot discover which emails have accounts.
   * *Can a student register as a professor?* No — the role is hard-coded to `STUDENT` in `registerStudent`; the form has no role field.
   * *What happens on logout?* The session is cleared, its generation number increases, running tasks are cancelled, and any late results are discarded.
   * *Why run login in a Task?* PBKDF2 and database calls take time; running them on the JavaFX thread would freeze the window.

## Module 3: Courses and lectures

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** A professor creates a course (assigned to themselves), enrolls students by enrollment number, optionally adds a CR as course staff with chosen permissions, adds lectures (draft) and publishes them. Students then see the lecture as an **upcoming-topic preview** with objectives and preparation instructions.
2. **Java concepts.** Enums with behaviour (`LectureStatus.next()` defines allowed transitions; `CoursePermission` maps to columns), records for models and forms, `java.time` (`LocalDate`, `LocalTime`, `ZoneId`, `Instant`).
3. **JavaFX.** `FlowPane` course cards, `TabPane` course detail, `TableView` students list, `DatePicker` and `ComboBox` lecture form inside a `Dialog`, clickable rows with keyboard support.
4. **Tables.** `courses`, `enrollments` (PK course+student), `course_staff` (composite FK to `enrollments`, so only enrolled CRs can be staff), `topics`, `lectures`.
5. **SQL and transactions.** Status changes use a *conditional update* `UPDATE lectures SET status=? WHERE id=? AND status=?` inside a transaction with `SELECT … FOR UPDATE`, so two users cannot both change the same lecture. Lists use `course_id = ANY(?)` with a JDBC array.
6. **Layers.** `CourseDetailPage` → `CourseService` / `LectureService` → `AccessPolicy` (permission check) → `CourseDao` / `LectureDao` → PostgreSQL.
7. **Key classes.** `AccessPolicy` (membership and CR permissions, read fresh every call), `CourseService.setStaffPermissions`, `LectureService.changeStatus`, `LectureService.visibleLecture`.
8. **Likely questions.**
   * *How are CR permissions course-scoped?* They are columns of a `course_staff` row for one course; `AccessPolicy.has()` reads that row on every operation, so revocation applies immediately.
   * *Why archive instead of delete?* Attendance and scores reference lectures and courses; archiving keeps history intact.
   * *Why `TIMESTAMPTZ`?* It stores an exact instant; the app converts to the academic time zone for display.
   * *Can students see draft lectures?* No — the SQL filter only returns drafts for courses where the user is professor or CR staff.
   * *What stops a CR granting themselves permissions?* Only the course professor passes `requireCourseProfessor` in `setStaffPermissions`.

## Module 4: Study material management

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** Professor/CR clicks *Upload material* → `FileChooser` → `MaterialService.upload` checks permission → `FileStorage.store` validates and copies the file to `uploads/materials/<uuid>.pdf` → metadata row inserted in a transaction → if the insert fails, the copied file is deleted. Students list, search, open or download (save-as) published materials.
2. **Java concepts.** `java.nio.file` (`Path`, `Files.copy`, `normalize`, `toRealPath`), `UUID`, byte-signature checks with `InputStream.readNBytes`, `java.awt.Desktop` to open with the OS default app.
3. **JavaFX.** `FileChooser` (open and save dialogs), `TableView` with custom `TableCell`s (type chip, action buttons), filter `ComboBox`es, search `TextField`.
4. **Tables.** `materials` (composite FK `(lecture_id, course_id)` keeps lecture and course consistent; `stored_path` is relative and unique).
5. **SQL.** Search uses `ILIKE ?` with escaped wildcards; sorting picks from a whitelist map (`MaterialDao.SORTS`), never user text; results are limited.
6. **Layers.** `MaterialsPage` → `MaterialService` → `AccessPolicy` + `FileStorage` → `MaterialDao` → PostgreSQL.
7. **Key methods.** `FileStorage.store/resolve/exportTo`, `MaterialService.upload/list/download/pathForOpening/setPublished`.
8. **Likely questions.**
   * *Why not store files in the database?* Large binary data makes backups and queries heavy; the DB keeps metadata and a relative path.
   * *How is path traversal prevented?* Stored names are generated; every resolved path is normalised and must stay under the uploads root (symbolic links are rejected too).
   * *Are file operations part of the JDBC transaction?* No — so the service copies first, inserts second, and deletes the copy if the insert fails.
   * *How do you stop a renamed .exe being uploaded as .pdf?* The first bytes must match the PDF signature `%PDF`.
   * *Is downloading permission-checked?* Yes — `download` and `pathForOpening` call the same `visible()` check as listing.

## Module 5: Lecture quizzes (with the Weak Topic Detector)

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** The professor creates a quiz (draft) from the Quizzes page or a lecture's *Quizzes* tab, adds MCQ or True/False questions (each with marks, an optional syllabus topic and an explanation) and publishes it. A student presses *Start* → `QuizService.start` creates an attempt whose deadline PostgreSQL computes → the attempt screen shows one question at a time with a countdown → every click is saved immediately (`saveAnswer`) → *Submit* (or the countdown reaching zero) calls `submit`, which grades the attempt in one transaction. The student sees the score and an answer review only when the quiz's review policy allows it. The professor's *Results* tab shows participation, average/highest/lowest, a bar chart of question accuracy, the most chosen wrong answers and class weak topics.
2. **Java concepts.** Records for every view model (`StudentQuestion`, `QuizAttempt`, `QuizAnalytics`…), enums with behaviour (`ReviewPolicy`, `AttemptStatus.finished()`), a pure rules class with no database (`QuizRules`) that is unit-tested, `Optional`, streams with `filter/map/sorted`, `ExecutorService` + `CountDownLatch` in the concurrency tests.
3. **JavaFX.** `Timeline` + `KeyFrame` for the 1-second countdown, `ToggleGroup` + `RadioButton` for answer choices, a `FlowPane` question navigator, `BarChart` with `CategoryAxis`/`NumberAxis` for accuracy, `TableView` for submissions, `TabPane` (Questions / Results), modal `Dialog`s for the quiz and question forms, and `sceneProperty()` to stop the timer when the page is closed.
4. **Tables.** `quizzes`, `questions`, `question_options` (holds the answer key `is_correct`), `quiz_attempts` (mode OFFICIAL/PRACTICE, status, `deadline_at`, score), `answers` (one row per attempt and question).
5. **SQL and transactions.**
   * Deadline: `least(clock_timestamp() + make_interval(mins => duration_minutes), closes_at)` inside the `INSERT … SELECT` that creates the attempt; the same statement only inserts while `opens_at <= now < closes_at`.
   * Autosave: `INSERT … ON CONFLICT (attempt_id, question_id) DO UPDATE` after locking the attempt row with `SELECT … FOR UPDATE` and checking the deadline against database time.
   * Submit: lock the attempt → `UPDATE answers SET is_correct = o.is_correct FROM question_options o …` → `SUM(marks) FILTER (WHERE is_correct)` → `UPDATE quiz_attempts … WHERE id = ? AND status = 'IN_PROGRESS'` — all in **one transaction**.
   * Constraints as the final defence: partial unique indexes (one correct option; one official attempt; one in-progress attempt), composite foreign keys so an answer cannot point at another quiz's question or another question's option.
   * Analytics: `count(*) FILTER (…)`, `DISTINCT ON` to find the most frequently chosen wrong option, `GROUP BY` topic for weak topics.
6. **Layers.** `QuizzesPage` / `QuizAttemptPage` / `QuizResultPage` / `QuizEditorPage` → `QuizService`, `QuizAnalyticsService` (+ `QuizRules`) → `AccessPolicy` → `QuizDao`, `AttemptDao` → PostgreSQL.
7. **Key methods.** `QuizService.start`, `session`, `saveAnswer`, `submit`, `result`, `review`, `finalizeExpired`, `publish`, `releaseResults`; `QuizAnalyticsService.analytics`, `weakTopicsForStudent`, `weakTopicsForCourse`; `QuizRules.resultsReleased`, `classify`.
8. **Definitions (denominators and missing data).**
   * Only finished **official** attempts count in analytics; practice attempts never do.
   * Participation = finished official attempts by currently enrolled students ÷ currently enrolled students. No enrolled students → no percentage.
   * Average / highest / lowest over finished official attempts. No submissions → shown as "—", never 0.
   * Question accuracy = correct ÷ finished official attempts (unanswered = incorrect).
   * **Weak Topic Detector** (counting, no AI): per syllabus topic, count questions *seen* and *correct* in finished official attempts. Fewer than 3 seen (student) or 10 answers (class) → "Not enough data yet". Accuracy below 60% → "Needs revision"; otherwise "On track". A student's weak topics use only **released** results, so the detector cannot leak correctness early. Questions without a topic are ignored.
9. **Likely questions.**
   * *Why doesn't the timer decide when time is up?* The JavaFX countdown is only a display. The deadline is stored in PostgreSQL and checked with database time on every save and submit, so changing the PC clock or killing the app does not give extra time.
   * *What if ClassSync crashes during a quiz?* Every answer is already saved. On reopening, *Resume* loads the same attempt with its saved answers and the remaining time. If the deadline passed meanwhile, the attempt is submitted automatically (status `EXPIRED`) with the answers saved before the deadline.
   * *What if the student double-clicks Submit, or the timer and the button fire together?* The attempt row is locked and the update only changes an `IN_PROGRESS` attempt, so the second call finds it finished and returns the same result. A test fires 8 submits at once and checks one score and one audit row.
   * *How are correct answers hidden?* Students receive `StudentQuestion`, a record that has no correct-answer or explanation field, built by a query that never selects those columns. The answer key reaches a student only through `review()`, which refuses until the policy releases results.
   * *Can the professor change a question after students start?* No. Once any attempt exists, questions and settings are frozen, so every student is graded on the same quiz. Editor changes lock the quiz row (`FOR UPDATE`) and starting an attempt takes a shared lock (`FOR SHARE`), so the two cannot interleave.
   * *What is a practice attempt?* A re-attempt allowed after results are released (if the professor enabled it). It is stored with `mode = 'PRACTICE'`, shown separately and excluded from marks and analytics.
   * *Can a CR edit quiz marks?* No. Quiz management is professor-only and not delegable; a test gives a CR every delegable permission and checks that creating questions, viewing analytics and releasing results are all refused.

## Module 6: Temporary-code attendance

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** The professor opens a lecture → *Attendance* tab → *Start attendance*. ClassSync shows a large 6-character code with a countdown. When it expires, a new code appears automatically. Students type the code on the *Attendance* page, the dashboard or the lecture tab. The professor watches the present count rise (the list refreshes every 4 seconds), then presses *Finalize*: everyone without a record becomes ABSENT and no more codes are accepted. Mistakes are fixed afterwards with *Correct*, which requires a reason.
2. **Java concepts.** `SecureRandom` for unpredictable codes; `MessageDigest` (SHA-256) and `HexFormat` for the stored hash; `MessageDigest.isEqual` for constant-time comparison; a private `enum Outcome` returned from a transaction so a wrong-code counter is committed *before* the error message is thrown; `OptionalDouble` for "no percentage yet".
3. **JavaFX.** Two `Timeline`s (a 1-second countdown and a 4-second roster refresh) stopped through `sceneProperty()` when the tab is closed; `ProgressBar` for code expiry and per-course percentages; `TableView` with custom `TableCell`s (status badges, *Correct* buttons); a `Dialog` with `ComboBox` + `TextArea` for corrections.
4. **Tables.** `attendance_sessions` (code hash, expiry, OPEN/CLOSED), `attendance` (one row per student per lecture), `attendance_code_failures`, `attendance_corrections`, plus `lectures.attendance_finalized_at`.
5. **SQL and transactions.**
   * Expiry: `code_expires_at = clock_timestamp() + make_interval(secs => ?)` — database time, not the PC clock.
   * Duplicates: the primary key `(lecture_id, student_id)` plus `INSERT … ON CONFLICT DO NOTHING`; the Java check before it only gives a friendlier message.
   * One live session: partial unique index `… (lecture_id) WHERE status = 'OPEN'`.
   * Finalize vs. submit: submit takes `SELECT … FOR SHARE` on the lecture row, finalize takes `FOR UPDATE`. Whichever comes second waits, so a student is either counted present before finalization or refused after it — never both. A test races 12 students against finalization three times.
   * Finalize: `INSERT INTO attendance … SELECT … FROM enrollments … ON CONFLICT DO NOTHING` adds the absentees in the same transaction that stamps the lecture.
   * Correction: update the record, insert the correction row (old, new, reason, actor, time) and the audit row in **one transaction**. A test uses a trigger to make the history insert fail and checks the status change was rolled back.
   * Reports: `count(*) FILTER (WHERE status = 'PRESENT')` grouped by course, lecture or student.
6. **Layers.** `AttendancePanel` / `AttendancePage` / dashboard → `AttendanceService` → `AccessPolicy` → `AttendanceDao`, `LectureDao.lockShared/lockById` → PostgreSQL.
7. **Key methods.** `AttendanceService.start`, `newCode`, `close`, `submitCode`, `finalizeAttendance`, `correct`, `summary`, `courseReport`, `overview`; `AttendanceService.hash`, `fraction`, `isLow`.
8. **Definitions.**
   * Counted lecture = attendance finalized **and** not cancelled. Drafts and unfinalized lectures never count.
   * Student percentage = present ÷ counted lectures in which the student has a record (enrolled at finalization). Nothing counted → "—", never 0%.
   * Low-attendance warning: below 75% with at least one counted lecture (exactly 75% is not low).
   * Course average = present records ÷ all records over finalized lectures.
9. **Likely questions.**
   * *Can a student share the code with a friend at home?* Yes — a code discourages casual misuse but cannot prove physical presence. That is why the professor reviews the list before finalizing and can correct it. ClassSync never claims codes eliminate proxies.
   * *Why store a hash instead of the code?* So the code is not readable in the database, backups or logs. Honestly, a 6-character code is quick to brute-force from its hash; the real protection is that it expires within minutes.
   * *How are wrong-code guesses limited?* Each wrong code increments `attendance_code_failures`; after 5 the student is refused for that session. The counter is in the database, so restarting the app does not reset it. A modified client could skip the check — documented as a limit of a desktop client talking directly to the database.
   * *What if the professor closes ClassSync during a session?* The session stays OPEN in the database; the code was only in that window's memory. Reopening the tab offers *Show a new code*, which replaces the code (the old one stops working).
   * *Who may correct attendance?* Only the course professor, only after finalization, always with a reason. A CR cannot — not even with every delegable permission (tested). The app's database role cannot UPDATE or DELETE correction history.

## Module 7: Lecture feedback

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** The professor opens a lecture's *Feedback* tab and ticks *Collect feedback* (and optionally *Anonymous*). Students see it on the Feedback page, the dashboard to-do list and the lecture tab, and rate understanding, clarity, pace and usefulness (1–5) with optional comments. They may edit while feedback is open; closing it makes answers final. The professor sees responses, participation, averages, comments and a per-course trend chart.
2. **Java/JavaFX.** Records for the report (`Summary`, `Comment`), `Double` null for "no data", `LineChart` with four `XYChart.Series`, `CheckBox` settings, `ComboBox<Integer>` ratings with a `StringConverter`.
3. **Tables/SQL.** `feedback` with `UNIQUE (lecture_id, student_id)`; `avg()` and `count(*)` aggregates; the comments query uses `CASE WHEN f.anonymous THEN NULL ELSE u.full_name END`, so anonymous names never leave PostgreSQL.
4. **Likely questions.**
   * *How is anonymity protected?* Report queries never select `student_id`, and return NULL instead of the name for anonymous rows. Feedback writes no audit-log entry. Once anonymous, a row stays anonymous even if the professor turns anonymity off later.
   * *Is it truly anonymous?* Only at the application level: the database keeps `student_id` to enforce one response per student, so a database administrator could link them. Students are told this in the form.
   * *Averages with no responses?* Shown as "—", never 0.

## Module 8: Student notes and approval

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** A student uploads notes (FileChooser) → status PENDING → the professor or a CR with *Review notes* approves (published) or requests changes (reason required) → the author fixes and resubmits (optionally with a new file) → PENDING again. Classmates see only APPROVED notes, can upvote them once, download them, and see professor recommendations.
2. **State machine.** PENDING → APPROVED | REJECTED; REJECTED/PENDING → (resubmit) → PENDING; APPROVED is final.
3. **Tables/SQL.** Notes are `materials` rows with `kind = 'NOTE'`. CHECK constraints tie `review_status` to notes and `published = (review_status = 'APPROVED')`. `material_approvals` is the history (append-only for the app role), `notes_votes` has PK `(material_id, voter_id)`. Review = lock row → check permission and "not own" → update status → insert history → notify author, all in one transaction.
4. **Likely questions.**
   * *How is self-approval prevented?* `NoteService.review` refuses when `uploader_id = reviewer`, and the review queue query excludes the reviewer's own notes — this also covers a CR who is both author and reviewer.
   * *Can another student find a pending note?* No: lists and search filter on `published OR uploader = me`; open/download use the same `canView` check (author or reviewer only); review notifications go only to the author.
   * *What happens to the old file on resubmission?* The new file is stored first; after the transaction commits the old file is deleted; if the transaction fails the new file is deleted instead.

## Module 9: Doubt forum and Doubt Heatmap

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** Any course member asks a doubt (optionally linked to a lecture, which sets its syllabus topic), others reply, upvote helpful replies, and the author, the professor or a CR with *Moderate discussions* marks it resolved. Moderators can hide inappropriate doubts or replies (kept in the database, hidden from students).
2. **Heatmap.** `SELECT topic, count(*) FILTER (WHERE resolved_at IS NULL), count(*) … GROUP BY topic` → JavaFX regions shaded by `unresolved / max` (darker = more unresolved).
3. **Tables.** `discussions` (resolved_at/by, hidden_at/by with CHECKs that they are set together), `discussion_replies`, `reply_votes` (PK). Search uses `ILIKE` with escaped `%`/`_`.
4. **Likely questions.**
   * *Who may resolve?* The author, the course professor, or a CR explicitly delegated moderation — a CR without that permission is refused (tested).
   * *Why hide instead of delete?* Accountability: moderators can review and undo; the audit log records who hid what.
   * *How is a professor answer recognised?* The reply's author is the course's `professor_id`.

## Module 10: Announcements and notifications

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** The professor (or a CR with *Publish announcements*) posts a categorised announcement to a course; every enrolled student is notified (and the professor, if a CR posted). Notifications also come automatically from other actions: lecture cancelled or room changed, quiz or material published, note approved/rejected, reply to or resolution of your doubt, low attendance after finalization. The bell in the top bar shows the unread count; clicking a notification marks it read and opens the related screen.
2. **Duplicate protection.** `notifications` has `UNIQUE (user_id, dedupe_key)` and every insert uses `ON CONFLICT DO NOTHING`. The key names the event (`announcement:42`, `quiz-published:7`, `reply:91`), so retries, double clicks or republishing never notify twice.
3. **Transactions.** Notification inserts use the same connection as the action, so they commit or roll back together (e.g. the announcement row and its notifications).
4. **Refresh strategy.** No server push: a JavaFX `Timeline` re-reads the unread count every 30 seconds and after each navigation; it stops at logout.
5. **Likely questions.**
   * *Why not email/SMS?* Out of scope by the brief; notifications are in-app only.
   * *How does one INSERT notify a whole class?* `INSERT INTO notifications … SELECT e.student_id FROM enrollments e WHERE e.course_id = ? AND e.student_id <> ?` — one statement, set-based.
   * *Can a user read someone else's notifications?* Every query has `WHERE user_id = ?` with the signed-in user's id; marking another user's notification read has no effect (tested).

## Module 11: Lecture revision mode

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** The Revision page has four tabs: completed lectures with their key concepts (objectives) and a *Mark topic revised* button; *Questions to revise* — the student's wrong or unanswered official questions with the correct answer and explanation, but only once results are released; *Practice quizzes* — re-attempts that never change official marks; and the revision history.
2. **SQL.** The mistakes query joins `quiz_attempts → questions → question_options (is_correct)` and LEFT JOINs the student's `answers`, keeping rows where the answer is missing or wrong; the release rule (`QuizRules.resultsReleased`) is applied in Java with database time.
3. **Likely questions.** *Can revision reveal answers early?* No — unreleased attempts are filtered out, exactly like the quiz review. *Why once per day?* `UNIQUE (student_id, topic_id, revised_on)` keeps the history honest and stops a streak being faked by clicking repeatedly.

## Module 12: Academic performance and achievements

### WHAT I NEED TO KNOW FOR MY VIVA

1. **What it shows.** Quiz average (released official results only), attendance, current and longest learning streak, a line chart of official quiz scores, a bar chart of weekly activity, weak topics, five badges, recent activity.
2. **Meaningful activity.** Submitting a quiz, marking attendance with a code, giving feedback, submitting notes, asking or answering a doubt, marking a topic revised, completing a study task. It is computed with one `UNION ALL` query over those tables — there is no "page viewed" event, so reopening a screen can never inflate activity.
3. **Streak.** Days are calendar days in the academic time zone (`(at AT TIME ZONE 'Asia/Kolkata')::date`). Current streak = consecutive active days ending today, or yesterday if nothing has happened yet today. `Streaks` is a pure class with unit tests.
4. **Badge rules** (awarded once — PK `(student_id, achievement_code)`; evaluated from records, so the result is deterministic; never revoked; separate from marks):
   * First Quiz — first finished official attempt.
   * Perfect Score — full marks in an official attempt whose results are released.
   * Five-Day Learning Streak — longest streak ≥ 5 days.
   * Helpful Contributor — ≥ 5 upvotes in total on the student's visible forum replies.
   * Notes Champion — ≥ 3 of the student's notes approved.
5. **Likely questions.** *Can a badge change a grade?* No — badges live in `student_achievements` and no code path reads them when computing scores or attendance. *Why not store activity events?* Deriving them avoids double bookkeeping and makes inflation impossible.

## Module 13: Professor semester planner

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** For each syllabus topic the professor sets a planned date, hours, unit and notes, and orders topics within a unit. The status comes from real lectures: a COMPLETED lecture covering the topic → *Completed*; an upcoming lecture → *Scheduled*; planned date passed with no lecture → *Overdue*; otherwise *Planned* / *Not planned*. Syllabus completion = completed topics ÷ all topics.
2. **Why derived?** Lectures already store `topic_id`, and students' upcoming-topic previews come from those lectures — deriving the status keeps the plan, the schedule and the previews in agreement with no second copy.
3. **Tables.** `teaching_plans` (PK `topic_id`), `topics.sequence` for order (rewritten in one transaction when a topic moves).

## Module 14: Central resource library

### WHAT I NEED TO KNOW FOR MY VIVA

1. **What it is.** One place for every published lecture material, previous-year paper, reference and approved student note in the user's courses, grouped by course and unit, with filters and search.
2. **No duplication.** It calls the same `MaterialService.list` used by the Study Materials page — the same rows, files and access rules. Nothing is copied.
3. **Likely question.** *Could the library show a pending note?* No — the same visibility query applies, and the library additionally shows only published items.

## Module 15: Bookmarks and personal study tracker

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** Bookmark materials/notes (library), lectures and doubts (their pages); add study tasks with optional due date and course, tick them off; see recent activity and revision history.
2. **Privacy.** Every query has `WHERE user_id = ?` with the signed-in user's id; another user's task id gives "not found". A bookmark is created only for something the user can see, and listing re-checks access — after unenrolment or unpublishing, the item disappears from the list.
3. **Constraints.** `bookmarks` has one nullable FK per target type with `CHECK (num_nonnulls(material_id, lecture_id, discussion_id) = 1)` and partial unique indexes, e.g. `UNIQUE (user_id, material_id) WHERE material_id IS NOT NULL`.


## Module 16: Administrator dashboard and user management

### WHAT I NEED TO KNOW FOR MY VIVA

1. **Workflow.** An `ADMIN` signs in → the sidebar shows only *Dashboard*, *User Management* and *Settings* → `AdminDashboardPage` (counts per role, newest accounts, recent sign-ins, account-change log) and `UsersPage` (search/filter table with row actions) → `AdminForms` dialogs → `AdminService` → `AdminDao`/`UserDao`/`AuditDao` → PostgreSQL.
2. **Java concepts.** Records for form data (`NewUser`, `Details`, `Overview`), `EnumMap` for counts per role, `SecureRandom` password generator without look-alike characters, `char[]` passwords wiped after hashing, a switch expression over `Role` for the badge colour.
3. **JavaFX.** `TableView` with custom `TableCell`s (role badges, status badges, a row of action buttons), `ComboBox` with a `StringConverter`, a dialog whose fields appear/disappear with the chosen role (`visible` + `managed`), confirmation dialogs before risky actions.
4. **Tables.** `users` (role check widened to `ADMIN` by migration **V007**), `students`, `professors`, `course_staff`, `audit_logs`. Admins have no profile row and no course access.
5. **SQL.** `ILIKE` search with `%`/`_` escaped; `SELECT … FOR UPDATE` on the target row; `SELECT id FROM users WHERE role='ADMIN' ORDER BY id FOR UPDATE` before disabling an admin; demoting a CR deletes their `course_staff` rows in the same transaction.
6. **Layers.** `UsersPage`/`AdminForms` → `AdminService` (all rules) → `AdminDao` → JDBC.
7. **Key methods.** `AdminService.create`, `updateDetails`, `changeRole`, `resetPassword`, `setActive`, `generatePassword`, `requireAdmin`; `AdminDao.lockAdmins`; `AuthService.provisionAdmin` (first admin from the command line).
8. **Likely questions.**
   * *The brief said three roles — why a fourth?* Account management needed a safe in-app owner. The admin role manages accounts only and has no academic rights, so the three academic roles and their permission matrix are unchanged. Registration still creates Students only.
   * *How is the first admin created?* With the `Provision admin` command, which needs the migration-owner credentials in `.env` — no one can make themselves an admin from the app.
   * *What stops a disabled admin who still has the window open?* `requireAdmin` re-reads the caller from the database on every call.
   * *Can the system end up with no administrator?* No: admins cannot disable themselves, and disabling an admin first locks every admin row in a fixed order, then re-checks the count. Without that lock, two admins disabling each other at once deadlocked (found by the 10-round race test).
   * *Why disable instead of delete?* Attendance, quiz results, notes and audit rows reference the user; disabling keeps the academic record and is reversible.
   * *Are passwords logged?* No — the audit log records "password reset by X", never the value; the generated password is shown once in the dialog for the admin to hand over.

## Whole-project questions examiners ask

* *Why PostgreSQL and not MySQL?* The brief asked for PostgreSQL. JDBC is identical (driver → `Connection` →
  `PreparedStatement` → `ResultSet` → transactions); only the driver jar, URL and a few SQL features differ
  (identity columns, `TIMESTAMPTZ`, partial indexes, `FILTER`, `ANY(?)` arrays).
* *Why no ORM or Spring?* The syllabus is about JDBC and JavaFX; writing the SQL by hand shows exactly what runs.
  Wiring is manual in `AppContext` (one instance of each service).
* *How do you stop SQL injection?* Every value is a `PreparedStatement` parameter. The only SQL built from text are
  fixed fragments chosen from a whitelist (sort orders) or from an enum (bookmark target column).
* *How do you keep the window responsive?* Database work runs on background threads (`Background`, JavaFX `Task`);
  results come back on the JavaFX thread; results of tasks started before logout are discarded.
* *What happens if two people do the same thing at once?* Row locks (`SELECT … FOR UPDATE / FOR SHARE`),
  conditional updates (`… WHERE status = 'IN_PROGRESS'`) and unique constraints. Tests race 6–12 threads for quiz
  submit/start and attendance submit/finalize.
* *Is this secure like a web server?* Partly. Rules run in the desktop client and in the database constraints;
  the app connects as a restricted role that cannot change the schema or rewrite audit/correction history. A
  modified client could skip client-side checks — a real deployment would move the services behind a server.
* *How does the schema change safely?* Numbered migration files with SHA-256 checksums in `schema_migrations`;
  each migration runs in its own transaction; existing data is never dropped; the app refuses to start on an
  older schema and says which command to run.
* *What would you improve next?* A small server API in front of the database, email/notification delivery,
  shared network storage for uploads, and accessibility testing with a screen reader.
