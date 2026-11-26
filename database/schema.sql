-- ClassSync full schema (GENERATED from database/migrations — do not edit by hand).
-- Regenerate with: mvnw exec:java@migrate -Dexec.args=schema
-- Fresh installs should use the migration tool so schema_migrations is populated.

-- ===== V001 accounts_courses =====
-- V001: accounts, courses, enrollment, CR delegation, topics, lectures, audit log.
-- PostgreSQL 17. Identity columns replace MySQL AUTO_INCREMENT.

CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(120) NOT NULL,
    full_name      VARCHAR(100) NOT NULL CHECK (length(trim(full_name)) > 0),
    role           VARCHAR(10)  NOT NULL CHECK (role IN ('STUDENT', 'PROFESSOR', 'CR')),
    password_hash  VARCHAR(200) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    theme          VARCHAR(5)   NOT NULL DEFAULT 'LIGHT' CHECK (theme IN ('LIGHT', 'DARK')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);
-- case-insensitive unique email
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

-- Students and CRs (a CR is a student with course-scoped staff rights)
CREATE TABLE students (
    user_id        BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    enrollment_no  VARCHAR(15) NOT NULL UNIQUE CHECK (enrollment_no ~ '^[A-Z0-9]{6,15}$'),
    department     VARCHAR(80) NOT NULL,
    semester       SMALLINT    NOT NULL CHECK (semester BETWEEN 1 AND 12)
);

CREATE TABLE professors (
    user_id        BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    employee_no    VARCHAR(15) NOT NULL UNIQUE,
    department     VARCHAR(80) NOT NULL,
    designation    VARCHAR(60) NOT NULL DEFAULT 'Professor'
);

CREATE TABLE courses (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(12)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9-]{3,12}$'),
    name           VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    department     VARCHAR(80)  NOT NULL,
    semester       SMALLINT     NOT NULL CHECK (semester BETWEEN 1 AND 12),
    description    VARCHAR(2000) NOT NULL DEFAULT '',
    professor_id   BIGINT       NOT NULL REFERENCES professors (user_id) ON DELETE RESTRICT,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX courses_professor_idx ON courses (professor_id);

CREATE TABLE enrollments (
    course_id      BIGINT      NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    student_id     BIGINT      NOT NULL REFERENCES students (user_id) ON DELETE RESTRICT,
    enrolled_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (course_id, student_id)
);
CREATE INDEX enrollments_student_idx ON enrollments (student_id);

-- CR delegation. The composite FK guarantees the CR is enrolled in the course,
-- and unenrolling automatically removes the delegation.
CREATE TABLE course_staff (
    course_id                 BIGINT  NOT NULL,
    user_id                   BIGINT  NOT NULL,
    can_update_lectures       BOOLEAN NOT NULL DEFAULT FALSE,
    can_review_notes          BOOLEAN NOT NULL DEFAULT FALSE,
    can_publish_announcements BOOLEAN NOT NULL DEFAULT FALSE,
    can_organize_resources    BOOLEAN NOT NULL DEFAULT FALSE,
    can_moderate_discussions  BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by                BIGINT  NOT NULL REFERENCES professors (user_id),
    granted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (course_id, user_id),
    FOREIGN KEY (course_id, user_id) REFERENCES enrollments (course_id, student_id) ON DELETE CASCADE
);
CREATE INDEX course_staff_user_idx ON course_staff (user_id);

CREATE TABLE topics (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id      BIGINT       NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    unit_no        SMALLINT     NOT NULL CHECK (unit_no BETWEEN 1 AND 10),
    name           VARCHAR(150) NOT NULL CHECK (length(trim(name)) > 0),
    sequence       INT          NOT NULL DEFAULT 0,
    UNIQUE (id, course_id)
);
CREATE UNIQUE INDEX topics_course_name_uq ON topics (course_id, lower(name));

CREATE TABLE lectures (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id                BIGINT       NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    lecture_no               INT          NOT NULL CHECK (lecture_no > 0),
    unit_no                  SMALLINT     NOT NULL CHECK (unit_no BETWEEN 1 AND 10),
    topic_id                 BIGINT,
    topic                    VARCHAR(150) NOT NULL CHECK (length(trim(topic)) > 0),
    description              VARCHAR(4000) NOT NULL DEFAULT '',
    objectives               VARCHAR(4000) NOT NULL DEFAULT '',
    preparation              VARCHAR(4000) NOT NULL DEFAULT '',
    room                     VARCHAR(40)  NOT NULL DEFAULT '',
    starts_at                TIMESTAMPTZ  NOT NULL,
    ends_at                  TIMESTAMPTZ  NOT NULL,
    status                   VARCHAR(10)  NOT NULL DEFAULT 'DRAFT'
                             CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED')),
    feedback_open            BOOLEAN      NOT NULL DEFAULT FALSE,
    feedback_anonymous       BOOLEAN      NOT NULL DEFAULT FALSE,
    attendance_finalized_at  TIMESTAMPTZ,
    attendance_finalized_by  BIGINT REFERENCES professors (user_id),
    created_by               BIGINT       NOT NULL REFERENCES users (id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (ends_at - starts_at <= INTERVAL '6 hours'),
    UNIQUE (course_id, lecture_no),
    UNIQUE (id, course_id),
    -- topic must belong to the same course
    FOREIGN KEY (topic_id, course_id) REFERENCES topics (id, course_id) ON DELETE RESTRICT
);
CREATE INDEX lectures_course_start_idx ON lectures (course_id, starts_at);
CREATE INDEX lectures_start_idx ON lectures (starts_at);

CREATE TABLE audit_logs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      BIGINT,
    details        VARCHAR(2000) NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_entity_idx ON audit_logs (entity_type, entity_id);

-- ===== V002 materials =====
-- V002: study materials, student notes (kind = 'NOTE' with a review status), review history, note upvotes.

CREATE TABLE materials (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id          BIGINT       NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    lecture_id         BIGINT,
    kind               VARCHAR(10)  NOT NULL CHECK (kind IN ('MATERIAL', 'NOTE', 'PYQ', 'REFERENCE')),
    title              VARCHAR(150) NOT NULL CHECK (length(trim(title)) > 0),
    description        VARCHAR(2000) NOT NULL DEFAULT '',
    unit_no            SMALLINT     CHECK (unit_no BETWEEN 1 AND 10),
    uploader_id        BIGINT       NOT NULL REFERENCES users (id),
    original_filename  VARCHAR(255) NOT NULL,
    stored_path        VARCHAR(255) NOT NULL UNIQUE CHECK (stored_path !~ '(^/|\.\.|\\|:)'),
    file_type          VARCHAR(5)   NOT NULL CHECK (file_type IN ('pdf', 'ppt', 'pptx', 'doc', 'docx', 'jpg', 'jpeg', 'png')),
    size_bytes         BIGINT       NOT NULL CHECK (size_bytes > 0),
    published          BOOLEAN      NOT NULL DEFAULT FALSE,
    review_status      VARCHAR(10)  CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    recommended        BOOLEAN      NOT NULL DEFAULT FALSE,
    download_count     INT          NOT NULL DEFAULT 0 CHECK (download_count >= 0),
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- the lecture (if any) must belong to the same course
    FOREIGN KEY (lecture_id, course_id) REFERENCES lectures (id, course_id) ON DELETE RESTRICT,
    -- notes always have a review status; other kinds never do
    CHECK ((kind = 'NOTE') = (review_status IS NOT NULL)),
    -- a note is visible to classmates only when approved
    CHECK (kind <> 'NOTE' OR published = (review_status = 'APPROVED'))
);
CREATE INDEX materials_course_idx ON materials (course_id, kind, created_at DESC);
CREATE INDEX materials_lecture_idx ON materials (lecture_id);
CREATE INDEX materials_uploader_idx ON materials (uploader_id);
CREATE INDEX materials_title_lower_idx ON materials (lower(title));

-- Review history for notes (append-only for the application role)
CREATE TABLE material_approvals (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    material_id  BIGINT      NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    actor_id     BIGINT      NOT NULL REFERENCES users (id),
    action       VARCHAR(12) NOT NULL CHECK (action IN ('SUBMITTED', 'RESUBMITTED', 'APPROVED', 'REJECTED')),
    comment      VARCHAR(1000) NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action <> 'REJECTED' OR length(trim(comment)) > 0)
);
CREATE INDEX material_approvals_material_idx ON material_approvals (material_id, created_at);

-- One upvote per voter per note
CREATE TABLE notes_votes (
    material_id  BIGINT      NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    voter_id     BIGINT      NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (material_id, voter_id)
);

-- ===== V003 quizzes =====
-- V003: lecture quizzes, questions, options, timed attempts and saved answers.
--
-- Integrity rules enforced here (the service layer checks them too, with friendlier messages):
--   * at most one correct option per question (partial unique index)
--   * an answer's option belongs to the answer's question, and the question to the attempt's quiz
--   * at most one OFFICIAL attempt and at most one IN_PROGRESS attempt per student per quiz
--   * attempt numbers are unique per student per quiz
--   * an attempt is scored exactly when it is finished
-- Total marks are not stored on the quiz: they are always SUM(questions.marks).

CREATE TABLE quizzes (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id            BIGINT        NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    lecture_id           BIGINT,
    title                VARCHAR(150)  NOT NULL CHECK (length(trim(title)) > 0),
    instructions         VARCHAR(4000) NOT NULL DEFAULT '',
    opens_at             TIMESTAMPTZ   NOT NULL,
    closes_at            TIMESTAMPTZ   NOT NULL,
    duration_minutes     INT           NOT NULL CHECK (duration_minutes BETWEEN 1 AND 300),
    status               VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    review_policy        VARCHAR(12)   NOT NULL DEFAULT 'AFTER_CLOSE'
                         CHECK (review_policy IN ('AFTER_SUBMIT', 'AFTER_CLOSE', 'MANUAL')),
    results_released_at  TIMESTAMPTZ,
    allow_practice       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by           BIGINT        NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (closes_at > opens_at),
    -- the lecture (if any) must belong to the same course
    FOREIGN KEY (lecture_id, course_id) REFERENCES lectures (id, course_id) ON DELETE RESTRICT
);
CREATE INDEX quizzes_course_idx ON quizzes (course_id, opens_at);
CREATE INDEX quizzes_lecture_idx ON quizzes (lecture_id);

CREATE TABLE questions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quiz_id      BIGINT        NOT NULL REFERENCES quizzes (id) ON DELETE CASCADE,
    position     INT           NOT NULL CHECK (position > 0),
    kind         VARCHAR(10)   NOT NULL CHECK (kind IN ('MCQ', 'TRUE_FALSE')),
    prompt       VARCHAR(2000) NOT NULL CHECK (length(trim(prompt)) > 0),
    marks        INT           NOT NULL CHECK (marks BETWEEN 1 AND 100),
    topic_id     BIGINT        REFERENCES topics (id) ON DELETE SET NULL,
    explanation  VARCHAR(2000) NOT NULL DEFAULT '',
    UNIQUE (quiz_id, position),
    UNIQUE (id, quiz_id)
);
CREATE INDEX questions_topic_idx ON questions (topic_id);

CREATE TABLE question_options (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id  BIGINT       NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    position     SMALLINT     NOT NULL CHECK (position BETWEEN 1 AND 6),
    text         VARCHAR(500) NOT NULL CHECK (length(trim(text)) > 0),
    is_correct   BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (question_id, position),
    UNIQUE (id, question_id)
);
-- never two correct options for one question
CREATE UNIQUE INDEX question_options_one_correct ON question_options (question_id) WHERE is_correct;

CREATE TABLE quiz_attempts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quiz_id       BIGINT       NOT NULL REFERENCES quizzes (id) ON DELETE RESTRICT,
    student_id    BIGINT       NOT NULL REFERENCES students (user_id),
    attempt_no    INT          NOT NULL CHECK (attempt_no > 0),
    mode          VARCHAR(10)  NOT NULL CHECK (mode IN ('OFFICIAL', 'PRACTICE')),
    status        VARCHAR(12)  NOT NULL DEFAULT 'IN_PROGRESS'
                  CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deadline_at   TIMESTAMPTZ  NOT NULL,
    submitted_at  TIMESTAMPTZ,
    score         INT          CHECK (score >= 0),
    max_score     INT          CHECK (max_score > 0),
    CHECK (deadline_at > started_at),
    CHECK ((status = 'IN_PROGRESS') = (submitted_at IS NULL)),
    CHECK ((status = 'IN_PROGRESS') = (score IS NULL)),
    CHECK (score IS NULL OR score <= max_score),
    UNIQUE (quiz_id, student_id, attempt_no),
    UNIQUE (id, quiz_id)
);
CREATE UNIQUE INDEX quiz_attempts_one_official ON quiz_attempts (quiz_id, student_id) WHERE mode = 'OFFICIAL';
CREATE UNIQUE INDEX quiz_attempts_one_open ON quiz_attempts (quiz_id, student_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX quiz_attempts_student_idx ON quiz_attempts (student_id, status);

CREATE TABLE answers (
    attempt_id   BIGINT      NOT NULL,
    quiz_id      BIGINT      NOT NULL,
    question_id  BIGINT      NOT NULL,
    option_id    BIGINT      NOT NULL,
    is_correct   BOOLEAN,                -- filled in when the attempt is scored
    saved_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, question_id),
    FOREIGN KEY (attempt_id, quiz_id) REFERENCES quiz_attempts (id, quiz_id) ON DELETE CASCADE,
    FOREIGN KEY (question_id, quiz_id) REFERENCES questions (id, quiz_id),
    FOREIGN KEY (option_id, question_id) REFERENCES question_options (id, question_id)
);
CREATE INDEX answers_question_idx ON answers (question_id);

-- ===== V004 attendance =====
-- V004: temporary-code attendance sessions, attendance records, wrong-code counters and corrections.
--
-- Rules enforced here (the service layer checks them too, with friendlier messages):
--   * at most one OPEN session per lecture (partial unique index)
--   * one attendance record per student per lecture (primary key) -> duplicate submissions are impossible
--   * the plain code is never stored: only SHA-256(session id + ":" + code)
--   * corrections are append-only for the application role (UPDATE/DELETE revoked by the migration tool)
-- Which lectures count: lectures with attendance_finalized_at set and status <> 'CANCELLED'.
-- Absences: finalization inserts an ABSENT row for every enrolled student without a record.

CREATE TABLE attendance_sessions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecture_id       BIGINT      NOT NULL REFERENCES lectures (id) ON DELETE RESTRICT,
    started_by       BIGINT      NOT NULL REFERENCES professors (user_id),
    status           VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    code_hash        CHAR(64)    NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    code_expires_at  TIMESTAMPTZ NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ,
    CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CHECK (code_expires_at > started_at)
);
CREATE UNIQUE INDEX attendance_sessions_one_open ON attendance_sessions (lecture_id) WHERE status = 'OPEN';
CREATE INDEX attendance_sessions_lecture_idx ON attendance_sessions (lecture_id, started_at DESC);

CREATE TABLE attendance (
    lecture_id   BIGINT      NOT NULL REFERENCES lectures (id) ON DELETE RESTRICT,
    student_id   BIGINT      NOT NULL REFERENCES students (user_id) ON DELETE RESTRICT,
    status       VARCHAR(8)  NOT NULL CHECK (status IN ('PRESENT', 'ABSENT')),
    source       VARCHAR(10) NOT NULL CHECK (source IN ('CODE', 'FINALIZE', 'CORRECTION')),
    session_id   BIGINT      REFERENCES attendance_sessions (id),
    marked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (lecture_id, student_id),
    -- a code submission always names the session it was made in
    CHECK (source <> 'CODE' OR session_id IS NOT NULL)
);
CREATE INDEX attendance_student_idx ON attendance (student_id);

-- Wrong-code attempts per student per session (the application refuses after a limit).
CREATE TABLE attendance_code_failures (
    session_id   BIGINT      NOT NULL REFERENCES attendance_sessions (id) ON DELETE CASCADE,
    student_id   BIGINT      NOT NULL REFERENCES students (user_id),
    failures     INT         NOT NULL CHECK (failures > 0),
    last_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, student_id)
);

-- Professor corrections after finalization: who, when, why, before and after.
CREATE TABLE attendance_corrections (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecture_id    BIGINT       NOT NULL,
    student_id    BIGINT       NOT NULL,
    old_status    VARCHAR(8)   NOT NULL CHECK (old_status IN ('PRESENT', 'ABSENT')),
    new_status    VARCHAR(8)   NOT NULL CHECK (new_status IN ('PRESENT', 'ABSENT')),
    reason        VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    corrected_by  BIGINT       NOT NULL REFERENCES professors (user_id),
    corrected_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (old_status <> new_status),
    FOREIGN KEY (lecture_id, student_id) REFERENCES attendance (lecture_id, student_id) ON DELETE RESTRICT
);
CREATE INDEX attendance_corrections_lecture_idx ON attendance_corrections (lecture_id, corrected_at);

-- ===== V005 collaboration =====
-- V005: lecture feedback, doubt forum, announcements and in-app notifications.
-- (Student notes reuse V002: materials kind 'NOTE', material_approvals, notes_votes.)

-- One feedback per student per lecture. student_id is kept ONLY to enforce eligibility and uniqueness;
-- when `anonymous` is true, professor-facing queries never select it.
CREATE TABLE feedback (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lecture_id          BIGINT        NOT NULL REFERENCES lectures (id) ON DELETE RESTRICT,
    student_id          BIGINT        NOT NULL REFERENCES students (user_id) ON DELETE RESTRICT,
    anonymous           BOOLEAN       NOT NULL,
    understanding       SMALLINT      NOT NULL CHECK (understanding BETWEEN 1 AND 5),
    clarity             SMALLINT      NOT NULL CHECK (clarity BETWEEN 1 AND 5),
    pace                SMALLINT      NOT NULL CHECK (pace BETWEEN 1 AND 5),
    usefulness          SMALLINT      NOT NULL CHECK (usefulness BETWEEN 1 AND 5),
    difficult_concepts  VARCHAR(1000) NOT NULL DEFAULT '',
    suggestions         VARCHAR(1000) NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (lecture_id, student_id)
);

-- Doubts ("discussions") and replies. Hidden items stay in the database for accountability.
CREATE TABLE discussions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id     BIGINT        NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    lecture_id    BIGINT,
    topic_id      BIGINT        REFERENCES topics (id) ON DELETE SET NULL,
    author_id     BIGINT        NOT NULL REFERENCES users (id),
    title         VARCHAR(200)  NOT NULL CHECK (length(trim(title)) > 0),
    body          VARCHAR(4000) NOT NULL DEFAULT '',
    resolved_at   TIMESTAMPTZ,
    resolved_by   BIGINT        REFERENCES users (id),
    hidden_at     TIMESTAMPTZ,
    hidden_by     BIGINT        REFERENCES users (id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((resolved_at IS NULL) = (resolved_by IS NULL)),
    CHECK ((hidden_at IS NULL) = (hidden_by IS NULL)),
    FOREIGN KEY (lecture_id, course_id) REFERENCES lectures (id, course_id) ON DELETE RESTRICT
);
CREATE INDEX discussions_course_idx ON discussions (course_id, created_at DESC);
CREATE INDEX discussions_lecture_idx ON discussions (lecture_id);
CREATE INDEX discussions_title_lower_idx ON discussions (lower(title));

CREATE TABLE discussion_replies (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    discussion_id  BIGINT        NOT NULL REFERENCES discussions (id) ON DELETE CASCADE,
    author_id      BIGINT        NOT NULL REFERENCES users (id),
    body           VARCHAR(4000) NOT NULL CHECK (length(trim(body)) > 0),
    hidden_at      TIMESTAMPTZ,
    hidden_by      BIGINT        REFERENCES users (id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((hidden_at IS NULL) = (hidden_by IS NULL))
);
CREATE INDEX discussion_replies_discussion_idx ON discussion_replies (discussion_id, created_at);

CREATE TABLE reply_votes (
    reply_id    BIGINT      NOT NULL REFERENCES discussion_replies (id) ON DELETE CASCADE,
    voter_id    BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (reply_id, voter_id)
);

CREATE TABLE announcements (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id   BIGINT        NOT NULL REFERENCES courses (id) ON DELETE RESTRICT,
    lecture_id  BIGINT,
    author_id   BIGINT        NOT NULL REFERENCES users (id),
    category    VARCHAR(20)   NOT NULL CHECK (category IN ('GENERAL', 'URGENT', 'CANCELLATION', 'ROOM_CHANGE',
                                                          'QUIZ', 'MATERIAL', 'ATTENDANCE_WARNING')),
    title       VARCHAR(200)  NOT NULL CHECK (length(trim(title)) > 0),
    body        VARCHAR(4000) NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (lecture_id, course_id) REFERENCES lectures (id, course_id) ON DELETE RESTRICT
);
CREATE INDEX announcements_course_idx ON announcements (course_id, created_at DESC);

-- In-app notifications. dedupe_key makes every notification write idempotent:
-- a retried action inserts with ON CONFLICT DO NOTHING and never notifies twice.
CREATE TABLE notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        VARCHAR(20)   NOT NULL CHECK (kind IN ('ANNOUNCEMENT', 'LECTURE', 'QUIZ', 'MATERIAL', 'NOTE',
                                                      'DISCUSSION', 'ATTENDANCE')),
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000) NOT NULL DEFAULT '',
    link_type   VARCHAR(20)   CHECK (link_type IN ('LECTURE', 'QUIZ', 'MATERIAL', 'NOTE', 'DISCUSSION',
                                                   'ANNOUNCEMENT', 'ATTENDANCE')),
    link_id     BIGINT,
    dedupe_key  VARCHAR(120)  NOT NULL,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, dedupe_key)
);
CREATE INDEX notifications_unread_idx ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);

-- ===== V006 progress =====
-- V006: revision history, achievements, semester planner, bookmarks and personal study tasks.
--
-- "Meaningful activity" (streaks, weekly activity) is NOT stored in a separate events table: it is derived
-- from records that already exist (submitted quizzes, code attendance, feedback, notes, doubts, replies,
-- revisions, completed tasks). Opening a screen therefore can never inflate activity.

-- Module 11: a student marks a syllabus topic as revised (at most once per topic per day).
CREATE TABLE revision_history (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id  BIGINT       NOT NULL REFERENCES students (user_id) ON DELETE CASCADE,
    topic_id    BIGINT       NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    revised_on  DATE         NOT NULL,           -- the day in the academic time zone
    revised_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note        VARCHAR(500) NOT NULL DEFAULT '',
    UNIQUE (student_id, topic_id, revised_on)
);
CREATE INDEX revision_history_student_idx ON revision_history (student_id, revised_at DESC);

-- Module 12: badge catalogue (fixed, deterministic rules documented in AchievementService) and awards.
CREATE TABLE achievements (
    code         VARCHAR(30)  PRIMARY KEY,
    name         VARCHAR(60)  NOT NULL,
    description  VARCHAR(300) NOT NULL
);
INSERT INTO achievements (code, name, description) VALUES
    ('FIRST_QUIZ', 'First Quiz', 'Submitted your first official quiz attempt.'),
    ('PERFECT_SCORE', 'Perfect Score', 'Scored full marks in an official quiz (counted once results are released).'),
    ('FIVE_DAY_STREAK', 'Five-Day Learning Streak', 'Learned on five consecutive days.'),
    ('HELPFUL_CONTRIBUTOR', 'Helpful Contributor', 'Your doubt-forum replies received at least 5 upvotes in total.'),
    ('NOTES_CHAMPION', 'Notes Champion', 'Three of your notes were approved for classmates.');

-- Awarded once per student per badge. Badges never affect marks or attendance.
CREATE TABLE student_achievements (
    student_id        BIGINT      NOT NULL REFERENCES students (user_id) ON DELETE CASCADE,
    achievement_code  VARCHAR(30) NOT NULL REFERENCES achievements (code),
    awarded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, achievement_code)
);

-- Module 13: the professor's plan for each syllabus topic (order comes from topics.sequence).
CREATE TABLE teaching_plans (
    topic_id       BIGINT       PRIMARY KEY REFERENCES topics (id) ON DELETE CASCADE,
    planned_date   DATE,
    planned_hours  SMALLINT     NOT NULL DEFAULT 1 CHECK (planned_hours BETWEEN 1 AND 20),
    notes          VARCHAR(500) NOT NULL DEFAULT '',
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Module 15: private bookmarks — exactly one target per row, each target bookmarked once per user.
CREATE TABLE bookmarks (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    material_id    BIGINT      REFERENCES materials (id) ON DELETE CASCADE,
    lecture_id     BIGINT      REFERENCES lectures (id) ON DELETE CASCADE,
    discussion_id  BIGINT      REFERENCES discussions (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(material_id, lecture_id, discussion_id) = 1)
);
CREATE UNIQUE INDEX bookmarks_material_uq ON bookmarks (user_id, material_id) WHERE material_id IS NOT NULL;
CREATE UNIQUE INDEX bookmarks_lecture_uq ON bookmarks (user_id, lecture_id) WHERE lecture_id IS NOT NULL;
CREATE UNIQUE INDEX bookmarks_discussion_uq ON bookmarks (user_id, discussion_id) WHERE discussion_id IS NOT NULL;

-- Module 15: private study tasks.
CREATE TABLE study_tasks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    course_id   BIGINT       REFERENCES courses (id) ON DELETE SET NULL,
    title       VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    due_date    DATE,
    done_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX study_tasks_user_idx ON study_tasks (user_id, done_at, due_date);
