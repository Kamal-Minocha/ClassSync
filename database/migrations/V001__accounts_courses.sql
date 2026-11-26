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
