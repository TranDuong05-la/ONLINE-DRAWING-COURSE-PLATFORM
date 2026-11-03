-- Flyway migration cho phần B (khóa học, module, bài học, quà tặng kèm, combo).
-- VERSION DỰ KIẾN: D dành V10 cho B; cần D và B review nội dung và phụ thuộc trước khi chạy ở bất kỳ database nào.
-- Viết theo mapping JPA của com.artacademy.course / bundle, cùng phong cách V1-V9 của D.
--
-- Quy ước đã thống nhất với D:
--   * id của phần B là INT (khớp A và D); tiền VND là BIGINT.
--   * Bảng products THUỘC E: file này KHÔNG tạo bảng products. course_included_kits chỉ lưu product_id (INT),
--     chưa có khóa ngoại sang products; khóa ngoại thêm bằng migration sau khi bảng của E đã có và hai bên đồng ý.
--     Vì vậy E chỉ tắt sản phẩm (active = false), không xóa cứng sản phẩm đang được tặng kèm.
--   * Combo: definition_version (bắt đầu 1) và position (1..10, không trùng trong cùng combo), tối đa 10 khóa.

-- Section: Courses

CREATE TABLE courses (
    id INT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    title VARCHAR(200) NOT NULL,
    short_description VARCHAR(500) NULL,
    description TEXT NULL,
    thumbnail_url VARCHAR(500) NULL,
    price BIGINT NOT NULL,
    discount_price BIGINT NULL,
    level VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    published_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    updated_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_courses_code UNIQUE (code),
    CONSTRAINT uk_courses_slug UNIQUE (slug),
    CONSTRAINT ck_courses_level CHECK (level IN ('BASIC', 'ADVANCED')),
    CONSTRAINT ck_courses_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_courses_price CHECK (price >= 0),
    CONSTRAINT ck_courses_discount CHECK (discount_price IS NULL OR (discount_price >= 0 AND discount_price < price)),
    KEY ix_courses_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_modules (
    id INT NOT NULL AUTO_INCREMENT,
    course_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL,
    is_hidden BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    KEY ix_course_modules_course (course_id),
    CONSTRAINT fk_course_modules_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_lessons (
    id INT NOT NULL AUTO_INCREMENT,
    module_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NULL,
    video_url VARCHAR(500) NULL,
    duration_seconds INT NOT NULL,
    material_url VARCHAR(500) NULL,
    sort_order INT NOT NULL,
    is_preview BOOLEAN NOT NULL,
    is_required BOOLEAN NOT NULL,
    is_hidden BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    KEY ix_course_lessons_module (module_id),
    CONSTRAINT fk_course_lessons_module FOREIGN KEY (module_id) REFERENCES course_modules (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_lessons_duration CHECK (duration_seconds >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_included_kits (
    id INT NOT NULL AUTO_INCREMENT,
    course_id INT NOT NULL,
    product_id INT NOT NULL COMMENT 'products.id của E; chưa có khóa ngoại, xem ghi chú đầu file',
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_course_included_kits_course_product UNIQUE (course_id, product_id),
    KEY ix_course_included_kits_product (product_id),
    CONSTRAINT fk_course_included_kits_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_included_kits_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Course bundles (combo)

CREATE TABLE course_bundles (
    id INT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NULL,
    price BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL,
    definition_version INT NOT NULL DEFAULT 1 COMMENT 'Tăng khi đổi mã, tên, giá, danh sách hoặc thứ tự khóa',
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    updated_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_course_bundles_code UNIQUE (code),
    CONSTRAINT ck_course_bundles_price CHECK (price >= 0),
    CONSTRAINT ck_course_bundles_version CHECK (definition_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE bundle_items (
    id INT NOT NULL AUTO_INCREMENT,
    bundle_id INT NOT NULL,
    course_id INT NOT NULL,
    position INT NOT NULL COMMENT 'Vị trí trong combo, 1..10',
    PRIMARY KEY (id),
    CONSTRAINT uk_bundle_items_bundle_course UNIQUE (bundle_id, course_id),
    CONSTRAINT uk_bundle_items_bundle_position UNIQUE (bundle_id, position),
    KEY ix_bundle_items_course (course_id),
    CONSTRAINT fk_bundle_items_bundle FOREIGN KEY (bundle_id) REFERENCES course_bundles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_items_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT ck_bundle_items_position CHECK (position BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
