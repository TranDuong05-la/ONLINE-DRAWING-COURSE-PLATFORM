-- Phần C: thông báo trong app (chuông ở trang chủ): được cấp khóa, bài tập mới, video mới, kết quả chấm bài,
-- chứng nhận. Mỗi người chỉ nhận một thông báo cho mỗi sự kiện (unique user_id + event_key).

CREATE TABLE learning_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    type VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_key VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NULL,
    course_id BIGINT NULL,
    lesson_id BIGINT NULL,
    target_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    read_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_learning_notifications_user_event UNIQUE (user_id, event_key),
    KEY ix_learning_notifications_user_created (user_id, created_at),
    CONSTRAINT fk_learning_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_notifications_type CHECK (type IN ('COURSE_ENROLLED', 'NEW_ASSIGNMENT', 'NEW_LESSON_VIDEO',
        'GRADED_APPROVED', 'GRADED_REVISION', 'CERTIFICATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
