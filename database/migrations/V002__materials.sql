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
