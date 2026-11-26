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
