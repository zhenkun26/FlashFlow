CREATE TABLE order_command_ledger (
    command_id CHAR(64) NOT NULL,
    operation_name VARCHAR(80) NOT NULL,
    caller_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    activity_sku_id VARCHAR(64) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_code VARCHAR(64),
    order_id CHAR(36),
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (command_id),
    CONSTRAINT uq_order_command_scope UNIQUE (operation_name, caller_id, idempotency_key),
    CONSTRAINT ck_order_command_schema CHECK (schema_version > 0),
    CONSTRAINT ck_order_command_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_order_command_status CHECK (
        status IN ('PREPARED', 'ACCEPTED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'RETRYABLE', 'UNRESOLVED')
    ),
    CONSTRAINT fk_order_command_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_order_command_status_updated (status, updated_at, command_id)
) ENGINE=InnoDB;
