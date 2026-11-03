-- Additive RBAC audit and JWT generation support. Never amend an applied migration.
ALTER TABLE users
    ADD COLUMN token_version INT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_users_token_version CHECK (token_version >= 1);

CREATE TABLE user_role_histories (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    changed_by_id INT NOT NULL,
    old_role VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    new_role VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reason VARCHAR(255) NULL,
    changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_user_role_histories_user_changed (user_id, changed_at, id),
    KEY ix_user_role_histories_actor (changed_by_id),
    CONSTRAINT fk_user_role_histories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_role_histories_actor FOREIGN KEY (changed_by_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_role_histories_old_role CHECK (old_role IN ('USER', 'INSTRUCTOR', 'STAFF', 'ADMIN')),
    CONSTRAINT ck_user_role_histories_new_role CHECK (new_role IN ('USER', 'INSTRUCTOR', 'STAFF', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
