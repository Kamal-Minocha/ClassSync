# ClassSync — Test Cases and Results

This file is the project's living test plan. It is rewritten as each roadmap section is completed.
Status values: **Pass** (automated test ran and passed), **Pass (manual)** (verified by running the app),
**Planned** (written for a section not built yet), **Not run** (exists but has not been executed in this environment).

_Last updated: 2026-11-25 — all 10 phases complete._

## How to run

| What | Command (Windows, from the project folder) | Needs |
|---|---|---|
| Unit tests only | `mvnw test` | nothing |
| Unit + integration tests | `mvnw verify` | `docker compose up -d postgres` |
| Tests inside Docker | `docker compose --profile test run --rm build-test` | Docker |
| Real-app screenshot run (drives the GUI) | `mvnw test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.classsync.tools.UiSnapshots "-Dexec.args=docs/screenshots all"` (last word: `all`, or one or more comma-separated steps of `student`, `professor`, `cr`, `quiz`, `attendance`, `collab`, `progress`, `handover`, `admin`, `startup`) | Docker + a desktop session |

* Unit tests are `*Test.java` (Surefire). Integration tests are `*IT.java` (Failsafe) and run against the **real PostgreSQL** database `classsync_test`.
* `TestDb.reset()` drops and re-creates the `public` schema of `classsync_test` and re-applies every migration before each test. It refuses to touch any database whose name does not end in `_test`, so the normal `classsync` database and uploads are never modified by tests.
* No H2/SQLite/mocks are used for database behaviour.

## Latest results

| Date | Where | Unit | Integration | Result |
|---|---|---|---|---|
| 2026-11-25 | Windows 11 host, JDK 24 (`--release 21`), PostgreSQL 17 in Docker | 29 / 29 | 126 / 126 | **BUILD SUCCESS** (Phase 9 code) |
| 2026-11-25 | `build-test` container, Maven offline | 29 / 29 | 126 / 126 | **BUILD SUCCESS** (Phase 9 code) |
| 2026-11-26 | **Admin dashboard and user management:** Windows 11 host suite + `UiSnapshots` `admin` step | 29 / 29 | 137 / 137 | **BUILD SUCCESS** (9 `AdminServiceIT` tests); 4 admin UI checks pass (22 earlier + 4 = 26); screenshots 120–124 |
| 2026-11-26 | `Provision admin` against `classsync_test` (password from `CLASSSYNC_PROVISION_PASSWORD`) | — | — | Created an active `ADMIN` account; confirmed in the database |
| 2026-11-26 | Theme redesign (Material Design 3, Thapar palette, campus photo backgrounds): host suite + full `UiSnapshots` run | 29 / 29 | 126 / 126 | BUILD SUCCESS; all 22 UI checks pass; all screenshots regenerated in the new theme (see DESIGN.md) |
| 2026-11-25 | Real JavaFX launch via `UiSnapshots` (all steps incl. handover + startup), final code | — | — | 62 screenshots; **22 UI behaviour checks passed** |
| 2026-11-25 | Host + offline container on final Phase 10 code | 29 / 29 | 126 / 126 | BUILD SUCCESS |
| 2026-11-25 | Host and container — Phase 8 code | 25 / 25 | 111 / 111 | BUILD SUCCESS |
| 2026-11-25 | Host and container — Phase 7 code | 25 / 25 | 83 / 83 | BUILD SUCCESS |
| 2026-11-25 | Windows 11 host — Phase 6 code | 21 / 21 | 67 / 67 | BUILD SUCCESS (3 consecutive runs) |
| 2026-11-25 | `build-test` container, Maven offline — Phase 6 code | 21 / 21 | 67 / 67 | BUILD SUCCESS |
| 2026-11-24 | `build-test` container on Phase 5 code | 17 / 17 | 42 / 42 | BUILD SUCCESS |

Failures seen on 2026-11-25 and what happened:

* All integration tests errored with *connection refused* (twice, after Docker Desktop had stopped). They passed once PostgreSQL was started.
* One container run failed with *Unknown host repo.maven.apache.org* — the image downloaded the JUnit test providers at run time. Fixed in the `Dockerfile` (providers are fetched while building the image); the suite now passes with Maven offline.
* `PlannerAndStudyIT.bookmarksArePrivateAndOnlyForVisibleItems` failed on its first run: the test expected "access denied" for an outsider, but the service (correctly) answers "not found" so it does not reveal that the file exists. The test expectation was fixed.
* `QZ-07` failed once with `expected PT15M but was PT15M0.000001S` — a real bug: the attempt INSERT read `clock_timestamp()` twice, so the deadline could be 1 µs later than start + duration. Fixed by reading the clock once per statement (`CROSS JOIN (SELECT clock_timestamp() AS now)`); 3 consecutive full runs pass.

---

## Phase 2 — PostgreSQL and JDBC foundation

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| DB-01 | Insert, select, update and delete a user with PreparedStatement | IT | `UserDaoIT.insertSelectUpdateDelete` | Pass |
| DB-02 | Email uniqueness is case-insensitive (SQLSTATE 23505) | IT | `UserDaoIT.duplicateEmailIgnoringCaseIsRejected` | Pass |
| DB-03 | CHECK constraints reject unknown role and malformed enrollment number (23514) | IT | `UserDaoIT.invalidRoleAndEnrollmentFormatAreRejectedByCheckConstraints` | Pass |
| DB-04 | Multi-statement transaction rolls back completely when the 2nd statement fails | IT | `UserDaoIT.transactionRollsBackEveryStatementOnFailure` | Pass |
| DB-05 | Transaction commits all statements on success | IT | `UserDaoIT.transactionCommitsWhenAllStatementsSucceed` | Pass |
| DB-06 | Re-running migrations is a no-op | IT | `MigrateIT.rerunningMigrationsIsANoOp` | Pass |
| DB-07 | Editing an already-applied migration is detected by checksum | IT | `MigrateIT.editedAppliedMigrationIsDetected` | Pass |
| DB-08 | A new migration upgrades the schema while keeping existing rows (no volume reset) | IT | `MigrateIT.newMigrationIsAppliedWithoutResettingData` | Pass |
| DB-09 | Restricted app role cannot run DDL or delete audit rows (42501) | IT | `MigrateIT.applicationRoleCannotChangeSchemaOrAuditLog` | Pass |
| DB-10 | `SchemaVersion.REQUIRED` equals the newest migration number | Unit | `SchemaVersionTest` | Pass |
| CFG-01 | `.env` parsing keeps backslashes, strips quotes/comments | Unit | `AppConfigTest` | Pass |
| CFG-02 | Missing secret gives a message pointing to `.env.example` | Unit | `AppConfigTest` | Pass |
| DOC-01 | Fresh volume: init script creates roles + `classsync` + `classsync_test`; health check turns healthy | Manual | `docker compose up -d postgres` | Pass (manual) |
| DOC-02 | Host JDBC URL `localhost:5432` works; container URL uses service host `postgres` | Manual | host `mvnw verify` + `build-test` container | Pass (manual) |
| DOC-03 | Upgrade existing dev database V001 → … → V006 without deleting the volume | Manual | `mvnw compile exec:java@migrate` | Pass (manual) |
| DOC-05 | Data survives the container being recreated (Docker Desktop restart, 2026-11-25): schema_migrations still showed V001–V002 applied, then upgraded to V003 | Manual | `docker compose up -d postgres` + `migrate status` | Pass (manual) |
| DOC-04 | Data survives `docker compose restart postgres` (users, attendance, notifications and UI-created records counted before/after) | Manual | `docker compose restart postgres` + psql counts | Pass (manual) |

## Phase 3 — JavaFX foundation

| ID | Test case | Type | Status |
|---|---|---|---|
| UI-01 | App launches; startup check shows login when DB is healthy | Manual (UiSnapshots) | Pass (manual) |
| UI-02 | Startup check shows "Cannot connect" screen with Retry and fix steps when PostgreSQL is unreachable | Automated UI check (`UiSnapshots` startup step, screenshot 108) | Pass |
| UI-03 | Startup check shows "schema needs an update" (required vs current version, command) when migrations are behind | Automated UI check (startup step, screenshot 109) | Pass |
| UI-04 | Light and dark themes render all screens with readable contrast | Manual (screenshots 01, 02, 15) | Pass (manual) |
| UI-05 | Sidebar collapses with Ctrl+B | Automated UI check (handover step, screenshot 95) | Pass |
| UI-06 | Window at 980×640: login hides the brand panel; dashboard and 7-tab lecture page still usable, sidebar scrolls | Automated UI check + screenshots 105–107 | Pass |
| UI-07 | Keyboard: TAB from the email field goes Password → Show password → Sign in → Create an account (JavaFX traversal, no OS input injected) | Automated UI check (handover step) | Pass — focus-ring appearance itself checked only visually |
| UI-08 | Long lists in tabbed pages scroll (regression for TabPane clipping found on 2026-11-24) | Manual (screenshot 22) | Pass (manual) |

## Phase 4 — Authentication and role shells

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| AUTH-01 | Registration always creates role STUDENT; login works by email or enrollment number (case-insensitive) | IT | `AuthServiceIT.registrationCreatesStudentOnlyAndLoginWorksByEmailOrEnrollment` | Pass |
| AUTH-02 | Stored password is a `pbkdf2_sha256$...` hash, never plain text | IT | `AuthServiceIT.passwordIsStoredAsPbkdf2HashNotPlainText` | Pass |
| AUTH-03 | Wrong password and unknown account return the same message | IT | `AuthServiceIT.wrongPasswordAndUnknownUserGiveTheSameMessage` | Pass |
| AUTH-04 | Duplicate email or enrollment number rejected | IT | `AuthServiceIT.duplicateEmailOrEnrollmentIsRejected` | Pass |
| AUTH-05 | Non-college email, bad enrollment format, weak password, mismatch rejected | IT | `AuthServiceIT.registrationValidation` | Pass |
| AUTH-06 | Deactivated account cannot sign in | IT | `AuthServiceIT.deactivatedAccountCannotSignIn` | Pass |
| AUTH-07 | Provisioned professor and CR accounts sign in with their roles | IT | `AuthServiceIT.provisionedRolesSignIn` | Pass |
| AUTH-08 | Password char[] is wiped after login | IT | `AuthServiceIT.loginClearsThePasswordArray` | Pass |
| AUTH-11 | Reset password (any role): policy enforced, old password stops working, unknown email reported | IT | `AuthServiceIT.resetPasswordReplacesTheOldOneForAnyRole` | Pass |
| AUTH-12 | Deactivated accounts cannot sign in until reactivated; unknown email reported | IT | `AuthServiceIT.deactivatedAccountsCannotSignInUntilReactivated` | Pass |
| AUTH-13 | Every provisioning command in ACCOUNTS_AND_CREDENTIALS.md (professor, student, cr, set-role, reset-password, set-active) run in PowerShell against `classsync_test` | Manual | `Provision` | Pass (manual) — found and fixed: on JDK 22+ the tool prompted on a non-interactive console and ignored `CLASSSYNC_PROVISION_PASSWORD`; the variable is now checked first |
| PW-01..07 | PBKDF2: verify, wrong password, unique salts, stored format, stored iterations honoured, malformed input, ≥600k default | Unit | `PasswordHasherTest` | Pass |
| AUTH-09 | All three roles sign in through the UI and see role-specific sidebar/dashboard | Manual (screenshots 10, 20, 30) | — | Pass (manual) |
| AUTH-10 | A background task started before logout delivers no result after logout (Session generation) | Automated UI check (handover step) | — | Pass |

## Phase 5 — Courses, lectures, materials (first learning loop)

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| CRS-01 | Professor creates course assigned to self; duplicate code rejected | IT | `CourseServiceIT.professorCreatesCourseAssignedToSelfWithUniqueCode` | Pass |
| CRS-02 | Students cannot create courses | IT | `CourseServiceIT.studentsCannotCreateCourses` | Pass |
| CRS-03 | Only the assigned professor manages enrollment; duplicate enrollment rejected | IT | `CourseServiceIT.onlyAssignedProfessorManagesEnrollmentAndDuplicatesAreRejected` | Pass |
| CRS-04 | Non-members (student or other professor) cannot read a course | IT | `CourseServiceIT.nonMemberCannotReadCourse` | Pass |
| CRS-05 | CR delegation requires enrollment and a CR account | IT | `CourseServiceIT.crDelegationRequiresEnrollmentAndCrRole` | Pass |
| CRS-06 | A CR cannot grant permissions to themselves | IT | `CourseServiceIT.crCannotGrantPermissionsToThemselves` | Pass |
| CRS-07 | Narrowing / removing a delegation takes effect on the very next check | IT | `CourseServiceIT.revocationTakesEffectOnNextCall` | Pass |
| CRS-08 | Unenrolling removes delegation via FK cascade | IT | `CourseServiceIT.unenrollingRemovesDelegationThroughForeignKeyCascade` | Pass |
| CRS-09 | Archive hides course but keeps it; other professors cannot restore it | IT | `CourseServiceIT.archivingHidesCourseFromDefaultListButKeepsIt` | Pass |
| LEC-01 | Draft lectures hidden from students until published; preview shows preparation | IT | `LectureServiceIT.draftsAreHiddenFromStudentsUntilPublished` | Pass |
| LEC-02 | Lecture numbers auto-assigned; times stored as instants and shown in academic zone | IT | `LectureServiceIT.lectureNumberIsAssignedAndTimesUseAcademicZone` | Pass |
| LEC-03 | End before start / unit out of range rejected | IT | `LectureServiceIT.validationRejectsBadTimes` | Pass |
| LEC-04 | Outsiders cannot read; students cannot create | IT | `LectureServiceIT.outsidersAndStudentsCannotCreateOrReadOtherCourses` | Pass |
| LEC-05 | CR needs UPDATE_LECTURES to publish; cancel is professor-only; revocation applies | IT | `LectureServiceIT.crNeedsDelegationToUpdateLecturesAndRevocationApplies` | Pass |
| LEC-06 | Invalid state transitions and edits of completed lectures rejected | IT | `LectureServiceIT.invalidTransitionsAreRejected` | Pass |
| LEC-07 | Only drafts without history can be deleted | IT | `LectureServiceIT.onlyDraftsWithoutHistoryCanBeDeleted` | Pass |
| LEC-08 | `%` and `_` in search are matched literally | IT | `LectureServiceIT.searchTreatsWildcardsLiterally` | Pass |
| MAT-01 | Professor upload stored under generated relative path; enrolled student downloads identical bytes; download counted | IT | `MaterialServiceIT.professorUploadIsStoredUnderManagedNameAndEnrolledStudentDownloadsIt` | Pass |
| MAT-02 | Outsiders cannot get, open, download or list | IT | `MaterialServiceIT.outsidersCannotListOpenOrDownload` | Pass |
| MAT-03 | Unpublished material hidden from students until published | IT | `MaterialServiceIT.unpublishedMaterialIsHiddenFromStudents` | Pass |
| MAT-04 | CR uploads by default but publishes only with ORGANIZE_RESOURCES | IT | `MaterialServiceIT.crUploadsByDefaultButPublishesOnlyWithOrganizePermission` | Pass |
| MAT-05 | Students cannot upload materials; no file left behind | IT | `MaterialServiceIT.studentsCannotUploadMaterials` | Pass |
| MAT-06 | Unsupported type, fake signature and oversized file rejected; no file left behind | IT | `MaterialServiceIT.unsupportedOversizedAndDisguisedFilesAreRejected` | Pass |
| MAT-07 | DB insert failure after copy deletes the copied file | IT | `MaterialServiceIT.failedDatabaseInsertRemovesCopiedFile` | Pass |
| MAT-08 | Unknown sort key falls back safely; SQL-like search text is just text | IT | `MaterialServiceIT.sortIsWhitelistedAndSearchIsParameterised` | Pass |
| FS-01 | `../`, absolute, drive-letter and empty paths rejected | Unit | `FileStorageTest.pathTraversalAndAbsolutePathsAreRejected` | Pass |
| FS-02 | Missing file gives a clear message | Unit | `FileStorageTest.missingFileGivesClearMessage` | Pass |
| FS-03 | Same source stored twice gets two names; nothing overwritten | Unit | `FileStorageTest.storeNeverOverwritesAndUsesGeneratedNames` | Pass |
| FS-04 | Display names stripped of paths/control chars and length-limited | Unit | `FileStorageTest.displayNameIsSanitised` | Pass |
| UI-09 | Student: dashboard previews, courses, week schedule, materials table, lecture preview | Manual (screenshots 10–14) | — | Pass (manual) |
| UI-10 | Professor: dashboard, course list, course detail tabs | Manual (screenshots 20–22) | — | Pass (manual) |
| UI-11 | CR: duties card with upload shortcut; Publish shown only where CR may publish | Manual (screenshots 30–31) | — | Pass (manual) |
| UI-12 | Upload through the native FileChooser dialog, then open with the OS default app | Manual | — | Not run — native OS file dialogs cannot be driven by the tool; upload/open services covered by MAT-01..07 |
| E2E-01 | Records created through the real dialogs (quiz, announcement, feedback, attendance correction) are still in PostgreSQL after the app process exits and after a PostgreSQL restart | Automated UI run + psql after exit | — | Pass |

## Phase 6 — Lecture quizzes

Timing rules under test: deadline = `least(start + duration, closes_at)` computed by PostgreSQL; saves and submits compare against `clock_timestamp()`; the JavaFX countdown is display only.

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| QZ-01 | Question validation: ≥2 options, no duplicate texts, correct option in range and not blank, marks ≥1; blank option fields skipped with the correct index re-mapped; True/False creates two options; total marks derived from questions | IT | `QuizServiceIT.questionValidationRules` | Pass |
| QZ-02 | Database allows at most one correct option per question (partial unique index, 23505) | IT | `QuizServiceIT.databaseAllowsAtMostOneCorrectOption` | Pass |
| QZ-03 | Publishing needs ≥1 question and a non-draft lecture | IT | `QuizServiceIT.publishRequiresQuestionsAndAVisibleLecture` | Pass |
| QZ-04 | Answer-key isolation: `StudentQuestion`/`Choice` have no correct/explanation fields; students cannot open the editor; score hidden and review refused before release | IT | `QuizServiceIT.studentModelsNeverCarryTheAnswerKey` | Pass |
| QZ-05 | MANUAL policy: review refused until the professor releases; CR cannot release; review shows unanswered questions | IT | `QuizServiceIT.manualReleaseOpensReview` | Pass |
| QZ-06 | Grading adds marks of correct answers only (7 / 10) | IT | `QuizServiceIT.gradingAddsMarksOfCorrectAnswersOnly` | Pass |
| QZ-07 | Deadline is the closing time when it comes first, otherwise start + duration | IT | `QuizServiceIT.deadlineIsTheEarlierOfDurationAndClosingTime` | Pass |
| QZ-08 | Cannot start before opening or at/after closing (boundary); states UPCOMING / MISSED | IT | `QuizServiceIT.quizCanOnlyBeStartedWhileOpen` | Pass |
| QZ-09 | Autosave: answers persist, can be changed, and a new service instance ("restart") resumes the same attempt with saved answers and remaining time | IT | `QuizServiceIT.savedAnswersSurviveARestartAndCanBeChanged` | Pass |
| QZ-10 | After the deadline a save is refused, the attempt becomes EXPIRED and is scored from answers saved in time; no edits afterwards | IT | `QuizServiceIT.expiredAttemptIsAutoSubmittedWithSavedAnswers` | Pass |
| QZ-11 | Expired attempts are finalised when the student next opens the quiz list (app was closed) | IT | `QuizServiceIT.expiredAttemptsAreFinalisedWhenTheStudentOpensTheQuizList` | Pass |
| QZ-12 | Double submit returns the same result and is audited once; no second official attempt | IT | `QuizServiceIT.submitIsIdempotent` | Pass |
| QZ-13 | 8 concurrent submits of one attempt: one `submitted_at`, one score, one audit row | IT | `QuizServiceIT.concurrentSubmitsScoreExactlyOnce` | Pass |
| QZ-14 | 6 concurrent starts create exactly one official attempt | IT | `QuizServiceIT.concurrentStartsCreateOneOfficialAttempt` | Pass |
| QZ-15 | Practice only after an official, released result; practice score separate; analytics count official only | IT | `QuizServiceIT.practiceAttemptsAreSeparateFromOfficialResults` | Pass |
| QZ-16 | Practice refused while results unreleased (AFTER_CLOSE) or when the quiz disallows it | IT | `QuizServiceIT.practiceIsRefusedWhenNotAllowedOrNotReleased` | Pass |
| QZ-17 | Questions and settings frozen once any attempt exists (add/edit/delete/unpublish refused) | IT | `QuizServiceIT.questionsAndSettingsFreezeOnceAnAttemptStarts` | Pass |
| QZ-18 | Only the course professor manages quizzes — not students, not a CR with every delegable permission, not another professor | IT | `QuizServiceIT.onlyTheCourseProfessorManagesQuizzes` | Pass |
| QZ-19 | Outsiders and professors cannot attempt; another student's attempt is "not found"; drafts invisible to students | IT | `QuizServiceIT.attemptsAreLimitedToEnrolledLearnersAndTheirOwnAttempts` | Pass |
| QZ-20 | An answer must belong to the attempt's quiz and the option to the question (composite FKs → friendly error) | IT | `QuizServiceIT.answerMustBelongToTheAttemptsQuiz` | Pass |
| QZ-21 | Analytics: no attempts → "—" not 0; participation 2/3; average 4.0; highest 5; lowest 3; per-question accuracy; most chosen wrong answer; best score first | IT | `QuizAnalyticsServiceIT.analyticsSummariseOfficialAttempts` | Pass |
| QZ-22 | Weak topics: 1/3 correct → WEAK; 2 questions seen → insufficient data; no attempts → nothing; class minimum 10 | IT | `QuizAnalyticsServiceIT.weakTopicsUseThresholdsAndMinimumSample` | Pass |
| QZ-23 | Unreleased results never feed a student's weak topics; after release they do | IT | `QuizAnalyticsServiceIT.unreleasedResultsNeverFeedTheStudentsWeakTopics` | Pass |
| QZ-24 | Unanswered questions count as seen and incorrect | IT | `QuizAnalyticsServiceIT.unansweredQuestionsCountAsSeenAndIncorrect` | Pass |
| QZ-26 | Whole flow (create, publish, start, autosave, submit, release, review, practice expiry, analytics) works as the restricted `classsync_app` role the desktop app uses | IT | `QuizServiceIT.wholeQuizFlowWorksWithTheRestrictedApplicationRole` | Pass |
| QZ-25 | Release rules per policy incl. exact closing instant; threshold boundaries (exactly 60% is not weak; 0 seen never divides) | Unit | `QuizRulesTest` (4 tests) | Pass |
| QZ-UI-01 | Student quiz list grouped Open / Upcoming / Completed with weak-topic card; dashboard pending-quiz and weak-topic cards | Manual (screenshots 10, 40) | — | Pass (manual) |
| QZ-UI-02 | Attempt screen: navigator, one question at a time, countdown, saved answers restored | Manual (screenshot 41) | — | Pass (manual) |
| QZ-UI-03 | Clicking an option in the real attempt screen autosaves it to PostgreSQL | Automated UI check in `UiSnapshots` (quiz step) | — | Pass |
| QZ-UI-04 | With the attempt screen open, the countdown reaching zero submits the attempt by itself | Automated UI check in `UiSnapshots` (6-second deadline) | — | Pass |
| QZ-UI-05 | Result page: score cards, answer review with correct/your answer, explanations, "only wrong" filter, practice button | Manual (screenshot 42) | — | Pass (manual) |
| QZ-UI-06 | Professor: quiz list, results tab (participation, average, accuracy chart, question table, submissions, class weak topics), editor with answer keys | Manual (screenshots 20, 43–45) | — | Pass (manual) |
| QZ-UI-07 | New-quiz dialog rejects an empty title (stays open, shows "Enter a title."), then creates the quiz; question dialog stores options with the selected correct one (91, 92) | Automated UI check | — | Pass |
| QZ-UI-08 | Submit confirmation dialog submits the attempt (97); lecture Quizzes tab | Automated UI check / manual | — | Pass (confirmation); lecture tab seen in screenshots |
| QZ-UI-09 | Quiz screens in the dark theme (101, 102) | Manual (screenshots) | — | Pass (manual) |

## Phase 7 — Temporary-code attendance

Rules under test: codes are 6 characters from a 31-letter alphabet (no 0/O/1/I/L), made with `SecureRandom`, valid for `CLASSSYNC_ATTENDANCE_CODE_SECONDS` (default 120); only SHA-256(lecture id + code) is stored; expiry uses database time; counted lectures = finalized and not cancelled.

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| ATT-01 | Correct code marks PRESENT (spaces/case forgiven); stored hash differs from and does not contain the code; the code never appears in the audit log | IT | `AttendanceServiceIT.correctCodeMarksPresentAndTheCodeIsNeverStoredOrLogged` | Pass |
| ATT-02 | Wrong codes are refused and counted (counter committed although the call fails); after 5 even the right code is refused; other students unaffected | IT | `AttendanceServiceIT.wrongCodesAreCountedAndLimited` | Pass |
| ATT-03 | Expired code refused (not counted as a wrong guess); "New code" keeps the session and the old code stops working at once | IT | `AttendanceServiceIT.expiredCodeIsRejectedAndANewCodeReplacesTheOldOne` | Pass |
| ATT-04 | Unenrolled students and professors cannot submit | IT | `AttendanceServiceIT.onlyEnrolledStudentsCanSubmit` | Pass |
| ATT-05 | Second submit refused by Java; direct duplicate INSERT refused by the primary key (23505) | IT | `AttendanceServiceIT.duplicateSubmissionsAreRejectedByJavaAndByTheDatabase` | Pass |
| ATT-06 | 6 concurrent submits by one student → exactly one success and one record | IT | `AttendanceServiceIT.concurrentSubmitsOfOneStudentCreateOneRecord` | Pass |
| ATT-07 | One OPEN session per lecture (starting again reuses it; partial unique index rejects a second); closed session refuses codes; a new session can follow | IT | `AttendanceServiceIT.oneOpenSessionPerLecture` | Pass |
| ATT-08 | Start refused for draft/cancelled lectures and more than 30 min early; finalize refused before the lecture starts | IT | `AttendanceServiceIT.startRules` | Pass |
| ATT-09 | Finalize closes the session, inserts ABSENT (source FINALIZE) for everyone without a record, then refuses codes, a second finalize and new sessions | IT | `AttendanceServiceIT.finalizationRecordsAbsenteesAndStopsSubmissions` | Pass |
| ATT-10 | 12 students submit while the professor finalizes (3 rounds): every student has exactly one record matching what their own call reported; finalize's present count equals the successful submits | IT | `AttendanceServiceIT.concurrentFinalizeAndSubmitsStayConsistent` | Pass |
| ATT-11 | Correction needs a reason and a real change; stores old/new/actor/reason; audited | IT | `AttendanceServiceIT.correctionStoresActorReasonAndBeforeAfter` | Pass |
| ATT-12 | Correction is atomic: a trigger makes the history insert fail → the status change and audit row are rolled back too | IT | `AttendanceServiceIT.correctionIsAllOrNothing` | Pass |
| ATT-13 | Corrections only after finalization and only by the course professor (not the CR, not another professor) | IT | `AttendanceServiceIT.correctionsNeedFinalizationAndTheCourseProfessor` | Pass |
| ATT-14 | A CR with every delegable permission cannot start, renew, close, finalize or view the roster; marks own attendance like a student | IT | `AttendanceServiceIT.crAndOtherProfessorsCannotRunSessionsOrFinalize` | Pass |
| ATT-15 | Percentages: no finalized lectures → no percentage; cancelled and unfinalized lectures excluded; 1/2 = 50% with warning; course report average 1/6; awaiting-finalization count | IT | `AttendanceServiceIT.percentagesCountOnlyFinalizedNonCancelledLectures` | Pass |
| ATT-16 | Whole flow as the restricted `classsync_app` role; the app role cannot UPDATE or DELETE correction history (42501) | IT | `AttendanceServiceIT.wholeFlowWorksWithTheRestrictedApplicationRoleAndCorrectionsAreAppendOnly` | Pass |
| ATT-17 | Codes: length, alphabet, no confusable characters, ~no repeats in 2000 draws; hash deterministic, salted per lecture, hides the code; 0/0 never divides; exactly 75% is not low; code lifetime bounded 15–900 s | Unit | `AttendanceRulesTest` (4 tests) | Pass |
| ATT-UI-01 | Professor presses the real "Start attendance" button; the big code, expiry bar and live count appear; two students submit the code read from the screen and the count updates by polling | Automated UI step in `UiSnapshots` (screenshot 50) | — | Pass |
| ATT-UI-02 | Student types the code (lower case) into the real code box and presses "Mark me present" → PRESENT in PostgreSQL | Automated UI check in `UiSnapshots` | — | Pass |
| ATT-UI-03 | Finalized lecture: roster with how each record was made, Correct buttons, correction history (screenshot 51); course report with awaiting-finalization list, per-lecture and per-student rates (52); student page and dashboard with warnings (53, 54, 10) | Manual (screenshots) | — | Pass (manual) |
| ATT-UI-04 | Finalize confirmation (93) and correction dialog (94) work end to end; automatic new code on expiry | Automated UI check | — | Pass (dialogs); code auto-renewal not observed live (service: ATT-03) |
| ATT-UI-05 | Pages sometimes opened half-scrolled (seen 3 times). Cause found in Phase 9: when the focused control disappeared with the old page, JavaFX moved focus into the new page and its ScrollPane scrolled to it. Fixed in `AppShell.open` | Manual (screenshots) | — | Fixed — see P9-UI-04 |

## Phase 8 — Collaboration (feedback, notes, doubts, announcements, notifications)

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| FB-01 | One feedback per student per lecture; a second submit edits the same row; direct duplicate INSERT refused (23505) | IT | `FeedbackServiceIT.oneFeedbackPerStudentAndEditingUpdatesTheSameRow` | Pass |
| FB-02 | Ratings outside 1–5 rejected | IT | `FeedbackServiceIT.ratingsMustBeBetweenOneAndFive` | Pass |
| FB-03 | Eligibility: outsiders, professors, not-started, closed (default) and cancelled lectures refused; CR may give feedback | IT | `FeedbackServiceIT.eligibilityRules` | Pass |
| FB-04 | Closing feedback freezes the student's answer | IT | `FeedbackServiceIT.closingFeedbackFreezesSubmissions` | Pass |
| FB-05 | Anonymity: anonymous author returned as NULL by the report query; stays anonymous after the professor switches anonymity off and after the student edits; report rows have no student id; no audit row identifies the author | IT | `FeedbackServiceIT.anonymousFeedbackNeverRevealsTheAuthor` | Pass |
| FB-06 | No responses → averages null ("—"); two responses → exact averages (4.0 / 4.0 / 4.0 / 3.0, overall 3.75), participation 2/3, trend row, pending list | IT | `FeedbackServiceIT.reportAggregatesAndParticipation` | Pass |
| FB-07 | Reports and trends only for the course professor (not a CR with every permission) | IT | `FeedbackServiceIT.onlyTheCourseProfessorSeesReports` | Pass |
| NT-01 | Pending note invisible to classmates in list, search, get and download; history hidden; visible to the author and to reviewers (professor and delegated CR) | IT | `NoteServiceIT.pendingNotesAreVisibleOnlyToTheAuthorAndReviewers` | Pass |
| NT-02 | Approval publishes, records SUBMITTED→APPROVED history, notifies only the author; second review and editing an approved note refused | IT | `NoteServiceIT.approvalPublishesRecordsHistoryAndNotifiesOnlyTheAuthor` | Pass |
| NT-03 | Rejection needs a reason (shown to the author); resubmission with a new file returns to PENDING, deletes the old file, records RESUBMITTED; only the author may resubmit | IT | `NoteServiceIT.rejectionNeedsAReasonAndResubmissionReturnsToReview` | Pass |
| NT-04 | A reviewer CR never sees or approves their own note; another reviewer can | IT | `NoteServiceIT.nobodyApprovesTheirOwnNoteEvenAReviewerCr` | Pass |
| NT-05 | Plain students, other professors and a CR whose delegation was just revoked cannot review; professors cannot submit notes | IT | `NoteServiceIT.revokedDelegationAndOutsidersAreRefused` | Pass |
| NT-06 | Upvotes unique per person, removable, not on pending notes, not on one's own note, not by professors (they recommend) | IT | `NoteServiceIT.upvotesAreUniqueAndNotForOwnOrPendingNotes` | Pass |
| NT-07 | "Most helpful" ranking orders approved notes by upvotes | IT | `NoteServiceIT.helpfulRankingOrdersByVotes` | Pass |
| DS-01 | Ask, reply; the author is notified once per reply by someone else, never for their own; professor replies marked; topic taken from the lecture | IT | `DiscussionServiceIT.askReplyAndNotifyTheAuthorOnce` | Pass |
| DS-02 | Outsiders cannot post, read a thread, reply or list | IT | `DiscussionServiceIT.outsidersCannotReadOrPost` | Pass |
| DS-03 | Resolve: other students and a CR without explicit moderation refused; delegated CR, author and professor allowed; author notified when someone else resolves; repeating is harmless | IT | `DiscussionServiceIT.resolveRightsAreAuthorProfessorOrDelegatedModerator` | Pass |
| DS-04 | Hiding: only moderators; hidden replies/doubts disappear for students (thread, list, reply) but stay visible and flagged for moderators; a delegated CR can unhide | IT | `DiscussionServiceIT.moderationHidesContentFromStudentsButNotModerators` | Pass |
| DS-05 | Reply upvotes unique, removable, not on one's own reply | IT | `DiscussionServiceIT.replyVotesAreUniqueAndNotForOwnReplies` | Pass |
| DS-06 | Search treats `%` literally; unresolved-only and mine-only filters | IT | `DiscussionServiceIT.searchFiltersAndWildcardsAreLiteral` | Pass |
| DS-07 | Heatmap: per-topic unresolved/total counts, most unresolved first, "General (no topic)" bucket; outsiders refused | IT | `DiscussionServiceIT.heatmapCountsUnresolvedDoubtsPerTopic` | Pass |
| AN-01 | Professor announcement: exactly one notification per enrolled student with a link; none for other courses or the author | IT | `AnnouncementServiceIT.professorAnnouncementReachesEveryEnrolledStudentOnce` | Pass |
| AN-02 | CR needs PUBLISH_ANNOUNCEMENTS (revocation applies); when a CR posts, the professor is notified; urgent titles marked | IT | `AnnouncementServiceIT.crNeedsDelegationAndTheProfessorIsToldWhenACrPosts` | Pass |
| AN-03 | Repeating the same notification event creates nothing new (dedupe key) | IT | `AnnouncementServiceIT.retriedNotificationsAreNeverDuplicated` | Pass |
| AN-04 | Read state is per user; marking someone else's notification has no effect; mark all read | IT | `AnnouncementServiceIT.readStateIsPerUser` | Pass |
| AN-05 | Room change of a published lecture and cancellation notify students | IT | `AnnouncementServiceIT.lectureCancellationAndRoomChangesNotifyStudents` | Pass |
| AN-06 | Quiz and material publishing notify once per item even after unpublish/republish; unpublished upload does not notify | IT | `AnnouncementServiceIT.publishingQuizzesAndMaterialsNotifiesOncePerItem` | Pass |
| AN-07 | Finalizing attendance warns students now below 75% (not those at 100%) | IT | `AnnouncementServiceIT.finalizingAttendanceWarnsStudentsBelowThreshold` | Pass |
| P8-UI-01 | Top-bar bell shows the unread count read from PostgreSQL | Automated UI check in `UiSnapshots` (collab step) | — | Pass |
| P8-UI-02 | Student: dashboard announcements + to-do (60), approved notes with upvotes/recommended (61), my submissions with statuses (62), forum with heatmap (63), thread with professor answer and votes (64), notifications (65) | Manual (screenshots) | — | Pass (manual) |
| P8-UI-03 | CR review queue shows only notes the CR may review and did not write (66) | Manual (screenshot) | — | Pass (manual) |
| P8-UI-04 | Professor: anonymous feedback report with settings and averages (67), trend chart (68), announcements (69), dashboard (70) | Manual (screenshots) | — | Pass (manual) |
| P8-UI-05 | Dialogs: feedback (96), ask doubt, new announcement, request-changes reason — each verified in PostgreSQL; note submit (native FileChooser) and posting a reply from the thread page | Automated UI check | — | Pass (4 dialogs); note upload and reply box not driven (services: NT-01..03, DS-01) |

## Phase 9 — Advanced modules (revision, performance, planner, library, bookmarks)

| ID | Test case | Type | Class / method | Status |
|---|---|---|---|---|
| ST-01 | Current streak ends today or yesterday; a missed day breaks it | Unit | `StreaksTest.currentStreakEndsTodayOrYesterday` | Pass |
| ST-02 | Many events on one day count as one day | Unit | `StreaksTest.duplicatesDoNotInflateStreaks` | Pass |
| ST-03 | Longest streak finds the best run | Unit | `StreaksTest.longestStreakFindsTheBestRun` | Pass |
| ST-04 | Weekly buckets start on Monday, include empty weeks, ignore older events | Unit | `StreaksTest.weeklyBucketsStartOnMondayAndIncludeEmptyWeeks` | Pass |
| PR-01 | Activity is derived from records; opening the performance page three times adds no activity | IT | `ProgressServiceIT.activityIsDerivedFromRecordsAndViewingAddsNothing` | Pass |
| PR-02 | Activity days use the academic time zone (20:00 UTC on 1 Jan = 2 Jan in Asia/Kolkata) | IT | `ProgressServiceIT.activityDaysUseTheAcademicTimeZone` | Pass |
| PR-03 | Five consecutive active days award FIVE_DAY_STREAK once; re-evaluation awards nothing and stores one row | IT | `ProgressServiceIT.streakBadgeIsAwardedOnceForFiveConsecutiveDays` | Pass |
| PR-04 | FIRST_QUIZ on the first official attempt; PERFECT_SCORE only after results are released; unreleased scores hidden; marks unchanged | IT | `ProgressServiceIT.quizBadgesRespectResultReleaseAndNeverChangeMarks` | Pass |
| PR-05 | Practice attempts are excluded from scores, averages and badges | IT | `ProgressServiceIT.practiceAttemptsStayOutOfPerformance` | Pass |
| PR-06 | HELPFUL_CONTRIBUTOR at 5 reply upvotes (not 4); NOTES_CHAMPION at 3 approved notes (not 2 + 1 pending) | IT | `ProgressServiceIT.contributionBadgesUseTheirThresholds` | Pass |
| PR-07 | Revision mistakes: none before release; after release wrong and unanswered questions with correct answer and explanation; correct answers never listed | IT | `ProgressServiceIT.mistakesShowOnlyReleasedWrongOrBlankAnswers` | Pass |
| PR-08 | Mark revised once per topic per day; outsiders and professors refused; history personal; performance is for students | IT | `ProgressServiceIT.revisionIsOncePerTopicPerDayAndOnlyForMembers` | Pass |
| PL-01 | Planner status derived from real lectures and plans: COMPLETED, SCHEDULED, OVERDUE, PLANNED, UNPLANNED (cancelled lectures cover nothing); completion 1/5; rescheduling clears OVERDUE | IT | `PlannerAndStudyIT.topicStatusIsDerivedFromRealLecturesAndPlans` | Pass |
| PL-02 | Empty syllabus → no percentage; members read; outsiders, students and a CR with every permission cannot edit | IT | `PlannerAndStudyIT.emptySyllabusHasNoPercentageAndMembersCanReadButOnlyTheProfessorEdits` | Pass |
| PL-03 | Topics move within their unit only; unit can be changed in the plan | IT | `PlannerAndStudyIT.topicsMoveWithinTheirUnitOnly` | Pass |
| BK-01 | Bookmarks private; duplicates ignored; unpublished files, draft lectures, other students' pending notes and other courses cannot be bookmarked (not found) | IT | `PlannerAndStudyIT.bookmarksArePrivateAndOnlyForVisibleItems` | Pass |
| BK-02 | A bookmark disappears from the list when access is lost (unpublished, unenrolled) and returns when access returns | IT | `PlannerAndStudyIT.bookmarksDisappearWhenAccessIsLost` | Pass |
| BK-03 | CHECK `num_nonnulls(...) = 1` rejects a bookmark without a target (23514) | IT | `PlannerAndStudyIT.bookmarkRowsHaveExactlyOneTarget` | Pass |
| BK-04 | Study tasks: validation, membership for course tasks, other users cannot complete/delete/see them; done/undone/delete | IT | `PlannerAndStudyIT.studyTasksArePrivate` | Pass |
| P9-UI-01 | Clicking an empty bookmark button in the real library saves exactly one bookmark in PostgreSQL | Automated UI check in `UiSnapshots` (progress step) | — | Pass |
| P9-UI-02 | Student: dashboard progress cards (80), revision lectures (81) and mistakes (82), performance with charts and badges (83), library by course/unit (84), bookmarks & tasks (85) | Manual (screenshots) | — | Pass (manual) |
| P9-UI-03 | Professor: semester planner with statuses and completion (86), dashboard syllabus completion (87) | Manual (screenshots) | — | Pass (manual) |
| P9-UI-04 | Pages open at the top after navigation (fix for ATT-UI-05: focus is parked on the sidebar entry before the page is swapped; 83 and 85 were captured half-scrolled before the fix and at the top after it) | Manual (screenshots, before/after) | — | Pass (manual) |
| P9-UI-05 | Planner "Plan" dialog reschedules a topic; "Add topic", task checkbox/delete, bookmark buttons on lecture and doubt pages | Automated UI check | — | Pass (Plan dialog; library bookmark P9-UI-01); the others covered by service tests PL-*, BK-* |

## Phase 10 — Handover verification

| ID | Check | How | Result |
|---|---|---|---|
| H-01 | Docker GUI profile shows the real app through WSLg on Windows 11 + Docker Desktop (no Linux distro), connecting to the `postgres` service by name | `docker compose --profile gui up -d --build classsync-gui`; window captured from inside the Linux display with `xwd` (screenshot 90) | Pass |
| H-02 | Backup and restore | `pg_dump -Fc -f` inside the container + `docker compose cp`, restored into a scratch database: users 9, quizzes 6, attendance 53, notifications 126, materials 15 — identical; scratch database dropped | Pass |
| H-03 | The previously documented PowerShell backup (`pg_dump ... > file`) | Reproduced: PowerShell 5.1 re-encodes the stream; `pg_restore` rejects the file | **Bug found and fixed in DOCKER.md** |
| H-04 | 13 real dialogs/confirmations driven through their actual buttons, each result checked in PostgreSQL | `UiSnapshots` handover step | Pass (see UI-02/03/05/06/07, AUTH-10, QZ-UI-07/08, ATT-UI-04, P8-UI-05, P9-UI-05) |
| H-05 | Dark theme on professor and student screens (100–104) | Screenshots | Pass (manual) |
| H-06 | Data survives app exit and a PostgreSQL restart | E2E-01, DOC-04 | Pass |
| H-07 | Full suite on host and in the offline container on the final code | `mvnw verify`; `build-test` with `mvn -o verify` | Pass (29 + 126) |

Problems found and fixed during Phase 10:

* The database-down screen stretched to the full window and cut its 4th instruction to "…" (trailing newline in the text);
  the instruction also missed `compile`. Fixed in `StartupView`.
* A failure inside the screenshot tool hung it forever (exec:java waits for the JavaFX thread). The tool now prints the
  error and exits with code 1.
* The Docker GUI compose service assumed a Linux X11 path; the socket folder is now configurable (`X11_SOCKET_DIR`).
* First screen-capture attempt of the Docker window grabbed whatever was in front (the editor) because Windows blocks
  bringing windows to the front; that image was deleted immediately and the window was captured from inside the
  Linux display instead.

## Admin dashboard — user management (added after Phase 10)

Adds the `ADMIN` role (migration V007), `AdminService`/`AdminDao`, the admin Dashboard and the User Management page.
This deliberately extends the brief's three-role model: administrators manage accounts only and have no course access.

| ID | Check | How | Test | Result |
|---|---|---|---|---|
| ADM-01 | Students, CRs and professors are refused every admin operation (overview, list, create, disable) | IT | `AdminServiceIT.onlyActiveAdminsCanManageUsers` | Pass |
| ADM-02 | An admin creates a Student, CR, Professor and Administrator; each signs in (email or enrollment number); profile rows match the role; 4 audit rows with the admin as actor; the password never appears in the audit log | IT | `AdminServiceIT.createsEveryKindOfAccountAndEachCanSignIn` | Pass |
| ADM-03 | Creation validation: email format, enrollment format, name/password rules, duplicate email (any case) and duplicate enrollment number give a clear message, not a database error | IT | `AdminServiceIT.creationIsValidated` | Pass |
| ADM-04 | Role switch only Student ↔ CR; CR → Student deletes all `course_staff` rows in the same transaction; professors cannot be turned into students and students cannot become professors | IT | `AdminServiceIT.roleSwitchIsStudentCrOnlyAndDemotionRemovesCourseRights` | Pass |
| ADM-05 | Reset password: old password stops working, new one works; disable blocks sign-in and keeps the row; enable restores sign-in | IT | `AdminServiceIT.resetPasswordAndDisableWork` | Pass |
| ADM-06 | An admin cannot disable themselves; a disabled admin is refused on their very next action | IT | `AdminServiceIT.adminsCannotDisableThemselvesAndADisabledAdminIsRefusedAtOnce` | Pass |
| ADM-07 | Two admins disable each other at the same moment (10 rounds, 2 threads): exactly one active admin remains every round | IT (concurrency) | `AdminServiceIT.mutualDisablingRaceAlwaysLeavesAnActiveAdmin` | Pass — **bug found and fixed**: without serialisation one transaction died with *deadlock detected*; the service now locks all ADMIN rows in id order and re-checks the caller. The test errors with the old code and passes with the fix |
| ADM-08 | The last active admin cannot be disabled | IT | covered by ADM-06 + ADM-07: the caller is always an active admin and cannot disable themselves, so the rule can only be reached through the concurrent case, which ADM-07 exercises | Pass |
| ADM-09 | List filters (role, disabled hidden by default, search by email; `%` matched literally) and dashboard counts | IT | `AdminServiceIT.listFiltersAndOverviewCounts` | Pass |
| ADM-10 | Create, reset password and disable work through the restricted `classsync_app` database role | IT | `AdminServiceIT.worksThroughTheRestrictedApplicationRole` | Pass |
| ADM-11 | V007 widens the role check: `ADMIN` accepted, an unknown role (`SUPERUSER`) still rejected | IT | `UserDaoIT.invalidRoleAndEnrollmentFormatAreRejectedByCheckConstraints` | Pass |
| ADM-UI-01 | Real app: admin dashboard (120), User Management (121), dark dashboard (124) | Manual (screenshots) | — | Pass (manual) |
| ADM-UI-02 | Real *Add user* dialog creates a professor with a **Generated** password, which then signs in (122) | Automated UI check (admin step) | — | Pass |
| ADM-UI-03 | Real *Reset password* dialog sets a password that then signs in | Automated UI check | — | Pass |
| ADM-UI-04 | Row **Disable** button + confirmation disables the account in PostgreSQL (123) | Automated UI check | — | Pass |
| ADM-UI-05 | Refreshing User Management keeps 8 table columns | Automated UI check | — | Pass — **bug found and fixed**: `AppShell.refresh()` re-runs `build()` on the same page, which appended a second set of columns and filter items (seen in screenshot 123). `UsersPage` and `MaterialsPage` (same pattern) now build their columns once and replace (not append) filter items; the role filter is kept across a refresh |
| ADM-UI-06 | Row buttons ("Make student", "Disable"), IDs and the "Class Representative" badge are shown in full at 1440 px; clipped name/email/details show the full text on hover | Manual (screenshots 121, 123) | — | Pass — **fixed**: buttons were cut to "Make stud…"/"Disa…"; the actions and Type columns now have minimum widths and buttons never shrink below their text |
| ADM-UI-07 | The Disable check uses the account owning the pressed button, not row 0 | Test fix | — | Pass — **test bug fixed**: table cells are recycled after a refresh, so scene order ≠ row order; the first rerun disabled a different account than the one checked |

## Not covered / known limits

* Native OS dialogs (FileChooser for uploads, Save-as) and opening files with the OS default app were not driven automatically (UI-12).
* Screen-reader accessibility was not tested; keyboard traversal and accessible texts on icon buttons exist.
* The Linux/X11 and Ubuntu-on-WSL variants of the GUI profile are documented but were only run as Docker Desktop + WSLg.
* Everything else in this file marked Pass was executed in this environment.


## Known environment limits

* The Docker GUI profile was verified on Windows 11 + Docker Desktop + WSLg (H-01).
* A `UiSnapshots` run uses `classsync_test` with demo data, never the normal database.
* Screenshots are captured by `UiSnapshots`, which opens the real application window and calls `Scene.snapshot` — they are not mock-ups.
