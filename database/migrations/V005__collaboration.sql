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
