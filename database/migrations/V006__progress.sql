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
