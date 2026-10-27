-- MANUAL REVIEW / ADMIN ONLY. Never executed by the app, build or integration tests.
-- Prerequisite: existing MySQL 8.0.16+ ai_task_platform.ai_text_task (InnoDB).
-- Additive migration only; no change/backfill/cleanup of existing tasks.
-- Intentionally no IF NOT EXISTS: an incompatible pre-existing table must fail visibly.
USE ai_task_platform;

CREATE TABLE ai_task_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    locked_until DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    isolated_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_outbox_task_type (task_id, event_type),
    KEY idx_outbox_due (status, next_attempt_at, event_id),
    KEY idx_outbox_expired (status, locked_until, event_id),
    CONSTRAINT fk_outbox_task FOREIGN KEY (task_id) REFERENCES ai_text_task(task_id),
    CONSTRAINT ck_outbox_type CHECK (event_type = 'TASK_SUBMITTED'),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','DISPATCHING','RETRY','SENT','ISOLATED')),
    CONSTRAINT ck_outbox_counters CHECK (attempts >= 0 AND version >= 0),
    CONSTRAINT ck_outbox_lifecycle CHECK (
        (status = 'PENDING' AND attempts = 0 AND claim_token IS NULL AND locked_until IS NULL
            AND sent_at IS NULL AND isolated_at IS NULL AND last_error_code IS NULL)
        OR (status = 'DISPATCHING' AND attempts > 0 AND claim_token IS NOT NULL AND locked_until IS NOT NULL
            AND sent_at IS NULL AND isolated_at IS NULL)
        OR (status = 'RETRY' AND attempts > 0 AND claim_token IS NULL AND locked_until IS NULL
            AND sent_at IS NULL AND isolated_at IS NULL AND last_error_code IS NOT NULL)
        OR (status = 'SENT' AND attempts > 0 AND claim_token IS NULL AND locked_until IS NULL
            AND sent_at IS NOT NULL AND isolated_at IS NULL AND last_error_code IS NULL)
        OR (status = 'ISOLATED' AND attempts > 0 AND claim_token IS NULL AND locked_until IS NULL
            AND sent_at IS NULL AND isolated_at IS NOT NULL AND last_error_code IS NOT NULL)
    )
) ENGINE=InnoDB;

-- The admin must inspect SHOW GRANTS for ai_task_app@127.0.0.1 after review.
-- Existing database-level SELECT/INSERT/UPDATE grants cover this new table.
-- Table-scoped grants would need those three privileges on this table, no DDL/DELETE.
-- No GRANT statements are executed by this migration or application.
