-- MANUAL ONLY. Phase 2A never initializes or migrates a database automatically.
-- Target: MySQL 8.0.16+ (enforced CHECK constraints), dedicated business database.
-- Review before execution; no DROP, no xxl_job objects, no automatic schema update.
-- IF NOT EXISTS does NOT upgrade an existing Phase 1 table; compare SHOW CREATE TABLE manually.
CREATE DATABASE IF NOT EXISTS ai_task_platform
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ai_task_platform;

CREATE TABLE IF NOT EXISTS ai_text_task (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_text VARCHAR(5000) NOT NULL,
    normalization_version VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NFC_LF_V1',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    output_text TEXT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_task_idempotency_key (idempotency_key),
    KEY idx_task_status_created (status, created_at),
    CONSTRAINT ck_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_task_normalization CHECK (normalization_version = 'NFC_LF_V1'),
    CONSTRAINT ck_task_version CHECK (version >= 0),
    CONSTRAINT ck_task_lifecycle CHECK (
        (status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL
            AND output_text IS NULL AND error_code IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL
            AND output_text IS NULL AND error_code IS NULL)
        OR (status = 'SUCCESS' AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND output_text IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND output_text IS NULL AND error_code IS NOT NULL)
    )
) ENGINE=InnoDB;
