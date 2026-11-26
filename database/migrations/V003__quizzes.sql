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
