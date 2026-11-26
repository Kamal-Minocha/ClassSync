package com.classsync.tools;

import com.classsync.config.Tx;
import com.classsync.model.AttemptMode;
import com.classsync.model.Question;
import com.classsync.model.QuestionKind;
import com.classsync.model.QuestionOption;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.User;
import com.classsync.service.AccessPolicy;
import com.classsync.service.QuizService;
import com.classsync.service.QuizService.QuestionForm;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Demo data for modules built after the first learning loop (quizzes, attendance, feedback,
 * notes, discussions, announcements, progress). Each module adds its seeding step here.
 */
final class DemoDataExtras {

    private DemoDataExtras() {
    }

    static void seed(DemoData demo, DemoData.World w) throws Exception {
        new Quizzes(demo, w).seed();
        new Attendance(demo, w).seed();
        new Collaboration(demo, w).seed();
        new Progress(demo, w).seed();
    }

    /**
     * Phase 9: a CS301 semester plan (one overdue and one future topic without lectures), Asha's revision history
     * on consecutive days (earning the five-day streak), her study tasks and bookmarks, then badge evaluation for
     * every student. Revision rows are back-dated with SQL because the service always records "today".
     */
    private static final class Progress {
        private final DemoData demo;
        private final DemoData.World w;
        private final AccessPolicy access = new AccessPolicy();

        Progress(DemoData demo, DemoData.World w) {
            this.demo = demo;
            this.w = w;
        }

        void seed() throws Exception {
            var db = demo.db();
            Clock clock = Clock.system(demo.zone());
            var lectures = new com.classsync.service.LectureService(db, access, clock);
            var storage = new com.classsync.util.FileStorage(demo.config().uploadRoot(), demo.config().maxUploadBytes());
            var materials = new com.classsync.service.MaterialService(db, access, storage, lectures);
            var forum = new com.classsync.service.DiscussionService(db, access, lectures);
            var study = new com.classsync.service.StudyService(db, access, materials, lectures, forum);
            var planner = new com.classsync.service.PlannerService(db, access, clock);
            var courses = new com.classsync.service.CourseService(db, access);
            var quizzes = new com.classsync.service.QuizService(db, access, clock);
            var progress = new com.classsync.service.ProgressService(db, access, clock,
                    new com.classsync.service.AttendanceService(db, access, 120),
                    new com.classsync.service.QuizAnalyticsService(db, access, quizzes));
            java.time.LocalDate today = java.time.LocalDate.now(demo.zone());

            // ---- semester plan for CS301 (dates follow the generated lecture schedule)
            int[][] plan = {{-21, 1}, {-14, 1}, {-7, 2}, {-2, 2}, {2, 2}, {5, 2}, {9, 2}};
            String[] names = {"Relational model", "SQL basics", "Joins and subqueries", "Normalization",
                    "Transactions and ACID", "Concurrency control", "Indexing"};
            for (int i = 0; i < names.length; i++) {
                planner.setPlan(w.meera, w.topics.get("CS301:" + names[i]), today.plusDays(plan[i][0]), plan[i][1],
                        i < 4 ? 1 + i / 2 : 3 + (i - 4) / 2, "");
            }
            long views = courses.addTopic(w.meera, w.dbms, 4, "Views and stored procedures");
            planner.setPlan(w.meera, views, today.plusDays(16), 2, 4, "Lab session with triggers");
            long optimisation = courses.addTopic(w.meera, w.dbms, 4, "Query optimisation");
            planner.setPlan(w.meera, optimisation, today.minusDays(1), 1, 4, "Slipped: needs a lecture slot");

            // ---- Asha: revisions on the four previous days + today → five-day streak
            User asha = w.students.get(0);
            Tx.inTransaction(db, con -> {
                String[] topics = {"CS301:Relational model", "CS301:SQL basics", "CS301:Joins and subqueries", "CS301:Normalization"};
                for (int i = 0; i < topics.length; i++) {
                    try (PreparedStatement ps = con.prepareStatement("""
                            INSERT INTO revision_history (student_id, topic_id, revised_on, revised_at, note)
                            VALUES (?, ?, ?, ?, 'Revised from notes')""")) {
                        java.time.LocalDate day = today.minusDays(4 - i);
                        ps.setLong(1, asha.id());
                        ps.setLong(2, w.topics.get(topics[i]));
                        ps.setObject(3, day);
                        ps.setObject(4, day.atTime(19, 30).atZone(demo.zone()).toOffsetDateTime());
                        ps.executeUpdate();
                    }
                }
                return null;
            });
            progress.markRevised(asha, w.topics.get("CS301:Joins and subqueries"), "Re-did the LEFT JOIN exercises");

            // ---- Asha's study tasks and bookmarks
            long done = study.addTask(asha, "Re-read normalization notes", today.minusDays(1), w.dbms);
            study.setTaskDone(asha, done, true);
            study.addTask(asha, "Finish JDBC lab write-up", today.minusDays(1), w.java);
            study.addTask(asha, "Practise 10 join queries", today.plusDays(2), w.dbms);
            study.addTask(asha, "Revise subnetting", today.plusDays(5), w.networks);
            Tx.inTransaction(db, con -> {
                try (var ps = con.prepareStatement(
                        "SELECT id FROM materials WHERE kind = 'NOTE' AND review_status = 'APPROVED' ORDER BY id LIMIT 1");
                     var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        study.bookmark(asha, com.classsync.dao.StudyDao.Target.MATERIAL, rs.getLong(1), true);
                    }
                }
                return null;
            });
            study.bookmark(asha, com.classsync.dao.StudyDao.Target.LECTURE, w.lectures.get("CS301-L6"), true);
            var doubts = forum.list(asha, new com.classsync.service.DiscussionService.Query(w.dbms, null, "self join", false, false, 1));
            if (!doubts.isEmpty()) {
                study.bookmark(asha, com.classsync.dao.StudyDao.Target.DISCUSSION, doubts.get(0).id(), true);
            }

            // ---- badges for everyone (deterministic: evaluated from the records above)
            List<User> learners = new java.util.ArrayList<>(w.students);
            learners.add(w.ravi);
            for (User s : learners) {
                progress.evaluateBadges(s);
            }
        }
    }

    /**
     * Phase 8: feedback, notes in every review state, doubts with replies, announcements. Notifications created by
     * the earlier seeding steps (material/quiz publishing, attendance warnings) are marked read first, so the bell
     * shows only the fresh, interesting ones.
     */
    private static final class Collaboration {
        private final DemoData demo;
        private final DemoData.World w;
        private final AccessPolicy access = new AccessPolicy();
        private final com.classsync.service.LectureService lectures;
        private final com.classsync.service.FeedbackService feedback;
        private final com.classsync.service.NoteService notes;
        private final com.classsync.service.DiscussionService forum;
        private final com.classsync.service.AnnouncementService announcements;

        Collaboration(DemoData demo, DemoData.World w) {
            this.demo = demo;
            this.w = w;
            var db = demo.db();
            var storage = new com.classsync.util.FileStorage(demo.config().uploadRoot(), demo.config().maxUploadBytes());
            lectures = new com.classsync.service.LectureService(db, access, Clock.system(demo.zone()));
            var materials = new com.classsync.service.MaterialService(db, access, storage, lectures);
            feedback = new com.classsync.service.FeedbackService(db, access);
            notes = new com.classsync.service.NoteService(db, access, storage, lectures, materials);
            forum = new com.classsync.service.DiscussionService(db, access, lectures);
            announcements = new com.classsync.service.AnnouncementService(db, access, lectures);
        }

        void seed() throws Exception {
            Tx.inTransaction(demo.db(), con -> {
                try (var st = con.createStatement()) {
                    st.executeUpdate("UPDATE notifications SET read_at = now()");
                }
                return null;
            });
            User asha = w.students.get(0);
            User neha = w.students.get(1);
            User kabir = w.students.get(2);
            User priya = w.students.get(3);
            User rohan = w.students.get(4);
            long l3 = w.lectures.get("CS301-L3");
            long l4 = w.lectures.get("CS301-L4");
            long j2 = w.lectures.get("CS305-L2");

            // ---- feedback: L3 anonymous, L4 named (Asha has not answered L4 yet), CS305 L2 named
            lectures.setFeedbackSettings(w.meera, l3, true, true);
            lectures.setFeedbackSettings(w.meera, l4, true, false);
            lectures.setFeedbackSettings(w.meera, j2, true, false);
            rate(asha, l3, 3, 3, 2, 4, "Correlated subqueries", "Please solve one more example on the board");
            rate(neha, l3, 4, 4, 3, 4, "", "Share the slides before class");
            rate(kabir, l3, 2, 3, 2, 3, "Outer joins with NULLs", "");
            rate(priya, l3, 4, 5, 4, 5, "", "");
            rate(w.ravi, l3, 4, 4, 4, 4, "", "More practice queries");
            rate(neha, l4, 5, 5, 4, 5, "", "The invoice example made 3NF click");
            rate(kabir, l4, 3, 3, 3, 4, "BCNF vs 3NF", "");
            rate(rohan, l4, 4, 4, 2, 4, "", "A bit fast near the end");
            rate(asha, j2, 4, 4, 4, 5, "", "");
            rate(neha, j2, 5, 4, 4, 5, "", "Loved the SQL injection demo");

            // ---- notes in every state
            long approved = note(neha, w.dbms, l3, "Joins cheat sheet", "All join types with Venn diagrams and examples.");
            notes.approve(w.meera, approved);
            long recommended = note(priya, w.dbms, l4, "Normalization worked examples", "1NF to 3NF on the invoice table.");
            notes.approve(w.ravi, recommended);   // the CR holds REVIEW_NOTES in CS301
            new com.classsync.service.MaterialService(demo.db(), access,
                    new com.classsync.util.FileStorage(demo.config().uploadRoot(), demo.config().maxUploadBytes()), lectures)
                    .setRecommended(w.meera, recommended, true);
            long rejected = note(kabir, w.dbms, l3, "Joins (rough)", "Quick photo of my notebook.");
            notes.reject(w.meera, rejected, "Pages 2 and 3 are too blurry to read. Please rescan in better light.");
            note(rohan, w.dbms, l4, "BCNF summary", "One-page summary of BCNF decomposition.");          // pending → Ravi's queue
            note(w.ravi, w.dbms, l3, "Subquery patterns", "EXISTS, IN and correlated subqueries.");      // pending → only Meera can review
            note(asha, w.java, j2, "PreparedStatement notes", "Parameters, batching and closing resources.");   // pending
            notes.vote(asha, approved, true);
            notes.vote(kabir, approved, true);
            notes.vote(w.ravi, approved, true);
            notes.vote(asha, recommended, true);

            // ---- doubts (Joins topic gets the most unresolved → hottest heatmap cell)
            long d1 = forum.ask(asha, new com.classsync.service.DiscussionService.DoubtForm(w.dbms, l3,
                    "Why does LEFT JOIN show NULLs for some columns?", "In the lab query, half the department names came back NULL."));
            long r1 = forum.reply(neha, d1, "Those employees have no matching department row, so the right side is filled with NULLs.");
            forum.reply(w.meera, d1, "Exactly. Use COALESCE(d.name, 'No department') if you want to show a label instead.");
            forum.voteReply(asha, r1, true);
            forum.voteReply(kabir, r1, true);
            forum.setResolved(asha, d1, true);
            forum.ask(kabir, new com.classsync.service.DiscussionService.DoubtForm(w.dbms, l3,
                    "When should I use a correlated subquery instead of a join?", ""));
            long d3 = forum.ask(priya, new com.classsync.service.DiscussionService.DoubtForm(w.dbms, l3,
                    "Is a self join just a join with an alias?", "Tried it on the employee/manager table."));
            forum.reply(w.ravi, d3, "Yes — the same table appears twice under two aliases.");
            forum.ask(rohan, new com.classsync.service.DiscussionService.DoubtForm(w.dbms, l4,
                    "Is every 3NF table also in BCNF?", ""));
            forum.ask(neha, new com.classsync.service.DiscussionService.DoubtForm(w.java, j2,
                    "Do I need to close the ResultSet if I close the Statement?", ""));

            // ---- announcements
            announcements.publish(w.meera, new com.classsync.service.AnnouncementService.AnnouncementForm(w.dbms, null,
                    com.classsync.model.AnnouncementCategory.GENERAL, "Mid-semester exam: Unit 1–2",
                    "The mid-semester exam covers the relational model, SQL basics, joins and normalization. Bring your ID card."));
            announcements.publish(w.ravi, new com.classsync.service.AnnouncementService.AnnouncementForm(w.dbms,
                    w.lectures.get("CS301-L6"), com.classsync.model.AnnouncementCategory.ROOM_CHANGE,
                    "Transactions lecture moves to LH-2", "LH-4 is booked for a seminar. Same time, LH-2 on the ground floor."));
            announcements.publish(w.meera, new com.classsync.service.AnnouncementService.AnnouncementForm(w.java, null,
                    com.classsync.model.AnnouncementCategory.URGENT, "Lab submission deadline extended to Friday",
                    "The JDBC lab is now due Friday 5 PM because of the network outage."));
        }

        private void rate(User u, long lecture, int a, int b, int c, int d, String difficult, String suggestion) throws SQLException {
            feedback.submit(u, lecture, new com.classsync.service.FeedbackService.FeedbackForm(a, b, c, d, difficult, suggestion));
        }

        private long note(User u, long course, long lecture, String title, String description) throws Exception {
            return notes.submit(u, new com.classsync.service.NoteService.NoteForm(course, lecture, title, description, null,
                    demo.pdf(title, description)));
        }
    }

    /**
     * Module 6. Past lectures get real sessions: codes are generated, the listed students submit them through
     * AttendanceService, and the professor finalizes (absentees recorded). Today's and future lectures are left
     * untouched so the professor can run a live session during a demo.
     *
     * Results: Asha 75% in CS301 and 50% in CS305 (low-attendance warning); Kabir 50% in CS301;
     * Neha's CS301 L2 absence is corrected to present with a reason.
     */
    private static final class Attendance {
        private final DemoData.World w;
        private final com.classsync.config.Database db;
        private final com.classsync.service.AttendanceService attendance;

        Attendance(DemoData demo, DemoData.World w) {
            this.w = w;
            this.db = demo.db();
            this.attendance = new com.classsync.service.AttendanceService(demo.db(), new AccessPolicy(), 120);
        }

        void seed() throws SQLException {
            // order of the pattern letters: Asha, Neha, Kabir, Priya, Rohan, Zoya, Ravi (P = present, - = absent)
            List<User> all = new java.util.ArrayList<>(w.students);
            all.add(w.ravi);
            take(w.meera, "CS301-L1", all, "PPPPPPP");
            take(w.meera, "CS301-L2", all, "P-PPPPP");
            take(w.meera, "CS301-L3", all, "PP-PP-P");
            take(w.meera, "CS301-L4", all, "-P--PPP");
            take(w.meera, "CS305-L1", all, "PPPPPPP");
            take(w.meera, "CS305-L2", all, "-PPPPPP");
            take(w.arjun, "CS310-L1", w.students.subList(0, 4), "PPP-");
            attendance.correct(w.meera, w.lectures.get("CS301-L2"), w.students.get(1).id(),
                    com.classsync.model.AttendanceStatus.PRESENT,
                    "Medical certificate submitted; was at the health centre during the code window.");
        }

        private void take(User prof, String lectureKey, List<User> students, String pattern) throws SQLException {
            long lecture = w.lectures.get(lectureKey);
            var live = attendance.start(prof, lecture);
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == 'P') {
                    attendance.submitCode(students.get(i), live.sessionId(), live.code());
                }
            }
            attendance.finalizeAttendance(prof, lecture);
            backdate(lecture);
        }

        /** Demo only: give the records the lecture's own times instead of the moment the demo was loaded. */
        private void backdate(long lecture) throws SQLException {
            Tx.inTransaction(db, con -> {
                for (String sql : List.of(
                        "UPDATE attendance_sessions s SET started_at = l.starts_at + interval '2 minutes',"
                                + " code_expires_at = l.starts_at + interval '4 minutes', closed_at = l.ends_at"
                                + " FROM lectures l WHERE l.id = s.lecture_id AND l.id = ?",
                        "UPDATE attendance a SET marked_at = l.starts_at + interval '3 minutes'"
                                + " FROM lectures l WHERE l.id = a.lecture_id AND a.source = 'CODE' AND l.id = ?",
                        "UPDATE attendance a SET marked_at = l.ends_at + interval '30 minutes'"
                                + " FROM lectures l WHERE l.id = a.lecture_id AND a.source = 'FINALIZE' AND l.id = ?",
                        "UPDATE lectures SET attendance_finalized_at = ends_at + interval '30 minutes' WHERE id = ?")) {
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setLong(1, lecture);
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        }
    }

    /**
     * Module 5. All quizzes are created through QuizService, so they obey the real rules. Past quizzes are
     * created open, attempted, then shifted back in time with one UPDATE (the only way to make history).
     *
     * Resulting demo states (relative to the day the demo is loaded):
     *   CS301 "SQL and joins check"  — closed 6 days ago, results released; Asha is weak on Joins
     *   CS301 "Normalization quiz"   — open now, answers shown after submit; Asha has NOT taken it yet
     *   CS305 "JDBC fundamentals"    — open now, results released manually; Asha submitted, awaiting release
     *   CS301 "Transactions warm-up" — opens in 2 days
     *   CS301 "Concurrency control"  — draft (professor only)
     */
    private static final class Quizzes {
        private final DemoData demo;
        private final DemoData.World w;
        private final QuizService quizzes;

        Quizzes(DemoData demo, DemoData.World w) {
            this.demo = demo;
            this.w = w;
            this.quizzes = new QuizService(demo.db(), new AccessPolicy(), Clock.system(demo.zone()));
        }

        void seed() throws Exception {
            User asha = w.students.get(0);
            User neha = w.students.get(1);
            User kabir = w.students.get(2);
            User priya = w.students.get(3);
            User rohan = w.students.get(4);
            User zoya = w.students.get(5);

            // ---- closed quiz with released results (AFTER_CLOSE), 6 questions on two topics
            long joins = create(w.meera, w.dbms, "CS301-L3", "SQL and joins check",
                    "Closed book. One official attempt.", -1, 2, 15, ReviewPolicy.AFTER_CLOSE, true);
            long tJoins = w.topics.get("CS301:Joins and subqueries");
            long tSql = w.topics.get("CS301:SQL basics");
            mcq(joins, "Which join returns every row of the left table, even without a match?", 2, tJoins,
                    "LEFT JOIN keeps unmatched left rows and fills the right side with NULLs.", 1, "INNER JOIN", "LEFT JOIN", "CROSS JOIN", "NATURAL JOIN");
            mcq(joins, "A subquery that refers to a column of the outer query is called…", 2, tJoins,
                    "It is re-evaluated for each outer row, hence 'correlated'.", 0, "a correlated subquery", "a scalar subquery", "a derived table");
            tf(joins, "An INNER JOIN can return more rows than the larger of the two tables.", 1, tJoins,
                    "Yes: one row can match many rows on the other side.", true);
            mcq(joins, "Which clause filters rows BEFORE grouping?", 1, tSql,
                    "WHERE filters rows; HAVING filters groups.", 0, "WHERE", "HAVING", "ORDER BY");
            mcq(joins, "Which keyword removes duplicate rows from a result?", 1, tSql,
                    "SELECT DISTINCT removes duplicates.", 2, "UNIQUE", "ONLY", "DISTINCT", "SINGLE");
            tf(joins, "ORDER BY is applied after SELECT computes the result columns.", 1, tSql,
                    "ORDER BY is evaluated last, so it may use column aliases.", true);
            quizzes.publish(w.meera, joins);
            attempt(joins, asha, "RWWRRR");    // weak on joins, strong on SQL basics
            attempt(joins, neha, "RRRRRR");
            attempt(joins, kabir, "RWRRWR");
            attempt(joins, priya, "WRWRRR");
            attempt(joins, rohan, "RRW-RW");
            attempt(joins, w.ravi, "RRRRWR");
            shiftToPast(joins, 6);

            // ---- open now, answers shown right after submit; Asha has not attempted it
            long norm = create(w.meera, w.dbms, "CS301-L4", "Normalization quiz",
                    "Think about functional dependencies before answering.", -24, 48, 20, ReviewPolicy.AFTER_SUBMIT, true);
            long tNorm = w.topics.get("CS301:Normalization");
            mcq(norm, "A table is in 1NF when…", 1, tNorm, "1NF requires atomic values: no repeating groups.", 0,
                    "every column holds atomic values", "it has a primary key of one column", "it has no NULLs");
            mcq(norm, "2NF removes which kind of dependency?", 2, tNorm,
                    "2NF removes partial dependencies on part of a composite key.", 1, "transitive", "partial", "multivalued");
            mcq(norm, "3NF removes which kind of dependency?", 2, tNorm,
                    "3NF removes transitive dependencies between non-key attributes.", 0, "transitive", "partial", "join");
            tf(norm, "Every relation in BCNF is also in 3NF.", 1, tNorm, "BCNF is stricter than 3NF.", true);
            mcq(norm, "Normalization mainly prevents…", 2, tNorm, "Redundancy causes update, insert and delete anomalies.", 2,
                    "slow joins", "large indexes", "update anomalies", "NULL values");
            quizzes.publish(w.meera, norm);
            attempt(norm, neha, "RRRRR");
            attempt(norm, kabir, "RRWRW");
            attempt(norm, priya, "RWRRR");

            // ---- CS305, manual release; Asha submitted and waits for results
            long jdbc = create(w.meera, w.java, "CS305-L2", "JDBC fundamentals",
                    "Answer from what you practised in the lab.", -3, 24, 15, ReviewPolicy.MANUAL, false);
            long tPrep = w.topics.get("CS305:PreparedStatement and ResultSet");
            long tArch = w.topics.get("CS305:JDBC architecture");
            mcq(jdbc, "Which object do you use to run a parameterised SQL statement?", 2, tPrep,
                    "PreparedStatement sends parameters separately, which also prevents SQL injection.", 1,
                    "Statement", "PreparedStatement", "Connection", "DriverManager");
            mcq(jdbc, "What does ResultSet.next() return when there are no more rows?", 1, tPrep,
                    "next() moves the cursor and returns false after the last row.", 2, "null", "an exception", "false");
            mcq(jdbc, "Which class hands out database connections from a JDBC URL?", 1, tArch,
                    "DriverManager.getConnection(url, user, password).", 0, "DriverManager", "ResultSet", "SQLException");
            tf(jdbc, "setAutoCommit(false) makes every statement commit immediately.", 1, tArch,
                    "It does the opposite: statements wait for commit() or rollback().", false);
            quizzes.publish(w.meera, jdbc);
            attempt(jdbc, asha, "RRWR");
            attempt(jdbc, zoya, "RWRR");

            // ---- upcoming and draft
            long tx = create(w.meera, w.dbms, "CS301-L6", "Transactions warm-up",
                    "Covers the ACID properties.", 48, 72, 10, ReviewPolicy.AFTER_CLOSE, true);
            long tAcid = w.topics.get("CS301:Transactions and ACID");
            mcq(tx, "Which ACID property guarantees that committed data survives a crash?", 1, tAcid,
                    "Durability.", 3, "Atomicity", "Consistency", "Isolation", "Durability");
            tf(tx, "ROLLBACK undoes every change made in the current transaction.", 1, tAcid, "That is atomicity in action.", true);
            quizzes.publish(w.meera, tx);

            long draft = create(w.meera, w.dbms, "CS301-L7", "Concurrency control (draft)",
                    "", 120, 144, 15, ReviewPolicy.AFTER_CLOSE, true);
            mcq(draft, "Which isolation level prevents dirty reads but allows non-repeatable reads?", 2,
                    w.topics.get("CS301:Concurrency control"), "", 1, "Read uncommitted", "Read committed", "Serializable");
        }

        private long create(User prof, long courseId, String lectureKey, String title, String instructions,
                            int opensInHours, int closesInHours, int minutes, ReviewPolicy policy, boolean practice) throws SQLException {
            ZonedDateTime now = ZonedDateTime.now(demo.zone()).withSecond(0).withNano(0);
            ZonedDateTime open = now.plusHours(opensInHours);
            ZonedDateTime close = now.plusHours(closesInHours);
            return quizzes.create(prof, new QuizService.QuizForm(courseId, w.lectures.get(lectureKey), title, instructions,
                    open.toLocalDate(), open.toLocalTime(), close.toLocalDate(), close.toLocalTime(), minutes, policy, practice));
        }

        private void mcq(long quiz, String prompt, int marks, Long topic, String explanation, int correct, String... options)
                throws SQLException {
            quizzes.addQuestion(w.meera, quiz, new QuestionForm(QuestionKind.MCQ, prompt, marks, topic, explanation, List.of(options), correct));
        }

        private void tf(long quiz, String prompt, int marks, Long topic, String explanation, boolean answer) throws SQLException {
            quizzes.addQuestion(w.meera, quiz, new QuestionForm(QuestionKind.TRUE_FALSE, prompt, marks, topic, explanation, null, answer ? 0 : 1));
        }

        /** One official attempt: per question R = correct, W = a wrong option, - = left unanswered. */
        private void attempt(long quiz, User student, String pattern) throws SQLException {
            List<Question> key = quizzes.editor(w.meera, quiz).questions();
            long attempt = quizzes.start(student, quiz, AttemptMode.OFFICIAL);
            for (int i = 0; i < pattern.length(); i++) {
                Question q = key.get(i);
                QuestionOption pick = switch (pattern.charAt(i)) {
                    case 'R' -> q.correctOption();
                    case 'W' -> q.options().stream().filter(o -> !o.correct()).findFirst().orElseThrow();
                    default -> null;
                };
                if (pick != null) {
                    quizzes.saveAnswer(student, attempt, q.id(), pick.id());
                }
            }
            quizzes.submit(student, attempt);
        }

        /** Moves a quiz and its attempts back in time so the quiz is closed `days` ago. */
        private void shiftToPast(long quiz, int days) throws SQLException {
            String interval = days + " days 3 hours";
            Tx.inTransaction(demo.db(), con -> {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE quizzes SET opens_at = opens_at - ?::interval, closes_at = closes_at - ?::interval WHERE id = ?")) {
                    ps.setString(1, interval);
                    ps.setString(2, interval);
                    ps.setLong(3, quiz);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement("""
                        UPDATE quiz_attempts SET started_at = started_at - ?::interval, deadline_at = deadline_at - ?::interval,
                            submitted_at = submitted_at - ?::interval WHERE quiz_id = ?""")) {
                    ps.setString(1, interval);
                    ps.setString(2, interval);
                    ps.setString(3, interval);
                    ps.setLong(4, quiz);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE answers SET saved_at = saved_at - ?::interval WHERE quiz_id = ?")) {
                    ps.setString(1, interval);
                    ps.setLong(2, quiz);
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }
}
