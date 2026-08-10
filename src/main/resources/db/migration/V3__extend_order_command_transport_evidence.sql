ALTER TABLE order_command_ledger
    ADD COLUMN publication_attempt_count INT NOT NULL DEFAULT 0 AFTER attempt_count,
    ADD COLUMN transport_cause VARCHAR(64) NULL AFTER publication_attempt_count,
    ADD COLUMN dead_lettered_at DATETIME(6) NULL AFTER completed_at,
    ADD CONSTRAINT ck_order_command_publication_attempts CHECK (publication_attempt_count >= 0);
