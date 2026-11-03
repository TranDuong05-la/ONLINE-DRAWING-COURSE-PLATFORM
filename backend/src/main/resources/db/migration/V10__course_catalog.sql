-- Flyway migration cho phần B (khóa học, module, bài học, quà tặng kèm, combo) và bảng products tối giản.
-- Viết theo mapping JPA của com.artacademy.course / bundle / product, cùng phong cách V1-V7 của D.
-- CHƯA chạy thử trên MySQL thật (máy phát triển không có MySQL/Docker): cần chạy MySqlSchemaCompatibilityIT
-- (xem backend/docs/MYSQL_INTEGRATION_TESTS.md) trên database thử riêng trước khi dùng chung.
-- Khác với D: id của phần B là BIGINT (Long), giá tiền VND là BIGINT. Giỏ hàng/đơn hàng của D lưu item_id kiểu INT
-- và không có FK sang các bảng này (cố ý, xem PHASE_4D_2C_2_CART_INTEGRATION.md); adapter chuyển Long <-> Integer.

-- Section: Products (TỐI GIẢN, E sẽ mở rộng; đừng đổi tên bảng/cột đang có)

CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    price BIGINT NOT NULL,
    thumbnail_url VARCHAR(500) NULL,
    active BOOLEAN NOT NULL,
    stock_quantity INT NOT NULL DEFAULT 0 COMMENT 'TẠM: tồn kho tối giản, E sẽ thay bằng tồn kho thật',
    PRIMARY KEY (id),
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    CONSTRAINT ck_products_stock CHECK (stock_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Courses

CREATE TABLE courses (
    id BIGINT NOT NULL AUTO_INCREMENT,
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
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL,
    is_hidden BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    KEY ix_course_modules_course (course_id),
    CONSTRAINT fk_course_modules_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE course_lessons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    module_id BIGINT NOT NULL,
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
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_course_included_kits_course_product UNIQUE (course_id, product_id),
    KEY ix_course_included_kits_product (product_id),
    CONSTRAINT fk_course_included_kits_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_course_included_kits_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT ck_course_included_kits_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Section: Course bundles (combo)

CREATE TABLE course_bundles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NULL,
    price BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    updated_at DATETIME(6) NULL COMMENT 'Zone-free LocalDateTime; not a UTC Instant',
    PRIMARY KEY (id),
    CONSTRAINT uk_course_bundles_code UNIQUE (code),
    CONSTRAINT ck_course_bundles_price CHECK (price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE bundle_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bundle_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bundle_items_bundle_course UNIQUE (bundle_id, course_id),
    KEY ix_bundle_items_course (course_id),
    CONSTRAINT fk_bundle_items_bundle FOREIGN KEY (bundle_id) REFERENCES course_bundles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_items_course FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
