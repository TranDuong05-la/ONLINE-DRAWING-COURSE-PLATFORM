-- Phân quyền giảng viên: ADMIN phân công giảng viên (role INSTRUCTOR) cho từng khóa học.
-- Một khóa có thể có nhiều giảng viên; giảng viên chỉ sửa nội dung / chấm bài của khóa được phân công.

CREATE TABLE course_instructors (
    id INT NOT NULL AUTO_INCREMENT,
    course_id INT NOT NULL,
    user_id INT NOT NULL,
    assigned_by_id INT NOT NULL,
    assigned_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_instructors_course_user (course_id, user_id),
    KEY ix_course_instructors_user (user_id),
    KEY ix_course_instructors_assigned_by (assigned_by_id),
    CONSTRAINT fk_course_instructors_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_instructors_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_instructors_assigned_by FOREIGN KEY (assigned_by_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
