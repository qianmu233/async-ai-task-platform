-- MANUAL ADMIN REVIEW ONLY. The application/build/tests NEVER execute this migration.
-- Target: existing MySQL 8.0.16+ ai_task_platform.ai_text_task.
-- Additive only: no data UPDATE/backfill/cleanup, no other schema changes.
-- Stop/drain older Worker binaries before enabling execution timeouts.
USE ai_task_platform;

ALTER TABLE ai_text_task
    ADD COLUMN execution_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN execution_deadline DATETIME(6) NULL,
    ADD KEY idx_task_execution_deadline (status, execution_deadline, task_id),
    ADD CONSTRAINT ck_task_execution_ownership CHECK (
        (execution_token IS NULL AND execution_deadline IS NULL)
        OR (status = 'RUNNING' AND execution_token IS NOT NULL AND execution_deadline IS NOT NULL
            AND started_at IS NOT NULL AND execution_deadline > started_at)
    );

-- Existing rows receive NULL/NULL metadata. Original ck_task_lifecycle is unchanged.
-- Legacy RUNNING rows with NULL/NULL are left untouched and excluded from timeout scans.
-- No reliable owner/deadline can be invented for them; resolve separately after manual review.
-- A protected terminal update MUST clear both fields. Old tokenless binaries cannot finish
-- a new protected RUNNING row without violating the added CHECK; do not mix deployment modes.
