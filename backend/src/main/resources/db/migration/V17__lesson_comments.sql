-- Forward-compatible schema addition from the latest team implementation.
-- V14-V16 in this branch remain authoritative; course and lesson identities are BIGINT.
CREATE TABLE lesson_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    lesson_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    author_user_id INT NOT NULL,
    author_role VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    parent_id BIGINT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    KEY ix_lesson_comments_lesson_created (lesson_id, created_at),
    KEY ix_lesson_comments_parent (parent_id),
    KEY ix_lesson_comments_course (course_id),
    KEY ix_lesson_comments_author (author_user_id),
    CONSTRAINT fk_lesson_comments_lesson FOREIGN KEY (lesson_id) REFERENCES course_lessons (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_comments_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_comments_author FOREIGN KEY (author_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_comments_parent FOREIGN KEY (parent_id) REFERENCES lesson_comments (id) ON DELETE RESTRICT,
    CONSTRAINT ck_lesson_comments_role CHECK (author_role IN ('USER', 'INSTRUCTOR', 'STAFF', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE learning_notifications DROP CHECK ck_learning_notifications_type;
ALTER TABLE learning_notifications
    ADD CONSTRAINT ck_learning_notifications_type CHECK (type IN (
        'COURSE_ENROLLED', 'NEW_ASSIGNMENT', 'NEW_LESSON_VIDEO', 'GRADED_APPROVED',
        'GRADED_REVISION', 'CERTIFICATE', 'NEW_COMMENT', 'COMMENT_REPLY'
    ));
