-- Flyway migration cho phần C (học tập): quyền học, giữ chỗ khóa theo Order, tiến độ, bài tập, bài nộp, chứng nhận.
-- Viết theo mapping JPA của com.artacademy.learning, cùng phong cách V1-V11.
-- Nối tiếp V11 (phần B). Không sửa file này sau khi đã chạy trên database dùng chung (Flyway kiểm tra checksum);
-- muốn đổi thì thêm V13.
-- Id riêng của C là BIGINT; course_id / lesson_id là INT theo courses/course_lessons của B; user/profile/order là INT theo A và D.
-- course_claims.order_id / order_item_id không có khóa ngoại sang orders/order_items (để hai phần độc lập schema).

-- Section: Quyền học (Course Ownership)

CREATE TABLE user_course_enrollments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    learner_profile_id INT NOT NULL,
    course_id INT NOT NULL,
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_type VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_order_id INT NULL,
    source_order_item_id INT NULL,
    completed_lessons_count INT NOT NULL,
    is_completed BOOLEAN NOT NULL,
    enrolled_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    completed_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    revoked_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    revoke_reason VARCHAR(150) NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_enrollments_learner_course UNIQUE (learner_profile_id, course_id),
    KEY ix_enrollments_user (user_id),
    KEY ix_enrollments_course (course_id),
    KEY ix_enrollments_order_item (source_order_item_id),
    CONSTRAINT fk_enrollments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_profile FOREIGN KEY (learner_profile_id) REFERENCES user_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_enrollments_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_enrollments_source CHECK (source_type IN ('ORDER', 'MANUAL')),
    CONSTRAINT ck_enrollments_completed_count CHECK (completed_lessons_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Giữ chỗ khóa theo Order của D (Course Claims)
-- active_key = 'learnerProfileId:courseId' khi RESERVED/CONFIRMED, NULL khi đã kết thúc.
-- UNIQUE(active_key) chặn hai Order cùng giữ một khóa (MySQL cho phép nhiều NULL trong cột UNIQUE).

CREATE TABLE course_claims (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    learner_profile_id INT NOT NULL,
    course_id INT NOT NULL,
    order_id INT NOT NULL,
    order_item_id INT NULL,
    source_type VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    component_position INT NULL,
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    active_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    reserved_at DATETIME(6) NOT NULL COMMENT 'UTC',
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC',
    confirmed_at DATETIME(6) NULL COMMENT 'UTC',
    released_at DATETIME(6) NULL COMMENT 'UTC',
    release_reason VARCHAR(50) NULL,
    refunded_at DATETIME(6) NULL COMMENT 'UTC',
    refund_operation_id VARCHAR(128) NULL,
    enrollment_id BIGINT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_course_claims_order_course UNIQUE (order_id, course_id),
    CONSTRAINT uk_course_claims_active_key UNIQUE (active_key),
    KEY ix_course_claims_order_item (order_item_id),
    KEY ix_course_claims_learner_course (learner_profile_id, course_id),
    CONSTRAINT fk_course_claims_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_claims_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_claims_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'REFUNDED')),
    CONSTRAINT ck_course_claims_source CHECK (source_type IN ('STANDALONE_COURSE', 'COMBO_COMPONENT')),
    CONSTRAINT ck_course_claims_active_key CHECK (
        (status IN ('RESERVED', 'CONFIRMED') AND active_key IS NOT NULL)
        OR (status IN ('RELEASED', 'REFUNDED') AND active_key IS NULL)),
    CONSTRAINT ck_course_claims_component CHECK (
        (source_type = 'STANDALONE_COURSE' AND component_position IS NULL)
        OR (source_type = 'COMBO_COMPONENT' AND component_position IS NOT NULL AND component_position >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Tiến độ bài học

CREATE TABLE user_lesson_progress (
    id BIGINT NOT NULL AUTO_INCREMENT,
    learner_profile_id INT NOT NULL,
    lesson_id INT NOT NULL,
    is_completed BOOLEAN NOT NULL,
    watched_seconds INT NOT NULL,
    last_position_seconds INT NOT NULL,
    last_watched_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    completed_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_lesson_progress_learner_lesson UNIQUE (learner_profile_id, lesson_id),
    KEY ix_lesson_progress_lesson (lesson_id),
    CONSTRAINT fk_lesson_progress_profile FOREIGN KEY (learner_profile_id) REFERENCES user_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lesson_progress_lesson FOREIGN KEY (lesson_id) REFERENCES course_lessons (id) ON DELETE RESTRICT,
    CONSTRAINT ck_lesson_progress_seconds CHECK (watched_seconds >= 0 AND last_position_seconds >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Đề bài tập

CREATE TABLE assignments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    lesson_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    instructions TEXT NULL,
    is_required BOOLEAN NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    updated_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_assignments_lesson UNIQUE (lesson_id),
    CONSTRAINT fk_assignments_lesson FOREIGN KEY (lesson_id) REFERENCES course_lessons (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Bài nộp (mỗi lần nộp một dòng, giữ lịch sử)

CREATE TABLE assignment_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    assignment_id BIGINT NOT NULL,
    lesson_id INT NOT NULL,
    course_id INT NOT NULL,
    learner_profile_id INT NOT NULL,
    image_key VARCHAR(255) NOT NULL,
    note TEXT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    score DECIMAL(5,2) NULL,
    feedback TEXT NULL,
    reviewed_by_user_id INT NULL,
    submitted_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    reviewed_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    version INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_submissions_attempt UNIQUE (assignment_id, learner_profile_id, attempt_no),
    KEY ix_submissions_status (status, submitted_at),
    KEY ix_submissions_learner (learner_profile_id),
    CONSTRAINT fk_submissions_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_submissions_learner FOREIGN KEY (learner_profile_id) REFERENCES user_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_submissions_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_submissions_status CHECK (status IN ('PENDING', 'APPROVED', 'NEEDS_REVISION')),
    CONSTRAINT ck_submissions_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_submissions_score CHECK (score IS NULL OR (score >= 0 AND score <= 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Chứng nhận hoàn thành

CREATE TABLE certificates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    certificate_code VARCHAR(50) NOT NULL,
    learner_profile_id INT NOT NULL,
    course_id INT NOT NULL,
    learner_name VARCHAR(100) NOT NULL,
    course_title VARCHAR(200) NOT NULL,
    pdf_key VARCHAR(255) NOT NULL,
    signature_hash VARCHAR(64) NOT NULL,
    issued_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    email_status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    email_attempts INT NOT NULL,
    last_email_attempt_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    email_sent_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    email_error VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_certificates_learner_course UNIQUE (learner_profile_id, course_id),
    CONSTRAINT uk_certificates_code UNIQUE (certificate_code),
    CONSTRAINT fk_certificates_profile FOREIGN KEY (learner_profile_id) REFERENCES user_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificates_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_certificates_email_status CHECK (email_status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_certificates_email_attempts CHECK (email_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
