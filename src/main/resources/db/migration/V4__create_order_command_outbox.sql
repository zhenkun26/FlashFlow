CREATE TABLE order_command_outbox (
    outbox_id CHAR(36) NOT NULL,
    command_id CHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    envelope_payload VARCHAR(2048) NOT NULL,
    envelope_fingerprint CHAR(64) NOT NULL,
    topic_name VARCHAR(128) NOT NULL,
    tag_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token CHAR(36),
    lease_owner VARCHAR(64),
    lease_until DATETIME(6),
    result_code VARCHAR(64),
    acknowledged_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (outbox_id),
    CONSTRAINT uq_order_command_outbox_command UNIQUE (command_id),
    CONSTRAINT fk_order_command_outbox_command FOREIGN KEY (command_id)
        REFERENCES order_command_ledger(command_id),
    CONSTRAINT ck_order_command_outbox_schema CHECK (schema_version > 0),
    CONSTRAINT ck_order_command_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_order_command_outbox_status CHECK (
        status IN ('READY', 'CLAIMED', 'RETRYABLE', 'ACKNOWLEDGED', 'INVALID', 'EXHAUSTED')
    ),
    CONSTRAINT ck_order_command_outbox_lease CHECK (
        (status = 'CLAIMED' AND lease_token IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'CLAIMED' AND lease_token IS NULL AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_order_command_outbox_ack CHECK (
        (status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL)
        OR
        (status <> 'ACKNOWLEDGED' AND acknowledged_at IS NULL)
    ),
    INDEX idx_outbox_dispatch_eligibility
        (status, next_attempt_at, lease_until, created_at, outbox_id),
    INDEX idx_outbox_command_status (command_id, status)
) ENGINE=InnoDB;
