CREATE TABLE activity (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_activity_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_activity_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB;

CREATE TABLE activity_sku_stock (
    id VARCHAR(64) NOT NULL,
    activity_id VARCHAR(64) NOT NULL,
    sku_code VARCHAR(100) NOT NULL,
    unit_price DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    initial_stock INT NOT NULL,
    available_stock INT NOT NULL,
    reserved_stock INT NOT NULL DEFAULT 0,
    sold_stock INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sku_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
    CONSTRAINT uq_activity_sku_code UNIQUE (activity_id, sku_code),
    CONSTRAINT ck_sku_price CHECK (unit_price >= 0),
    CONSTRAINT ck_stock_nonnegative CHECK (
        initial_stock >= 0 AND available_stock >= 0 AND reserved_stock >= 0 AND sold_stock >= 0
    ),
    CONSTRAINT ck_stock_conservation CHECK (
        initial_stock = available_stock + reserved_stock + sold_stock
    )
) ENGINE=InnoDB;

CREATE TABLE orders (
    id CHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    activity_sku_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    unit_price DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_sku FOREIGN KEY (activity_sku_id) REFERENCES activity_sku_stock(id),
    CONSTRAINT ck_order_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CLOSED_UNPAID')),
    INDEX idx_order_expiration (status, expires_at, id),
    INDEX idx_order_user_sku_created (user_id, activity_sku_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE purchase_claim (
    activity_sku_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    order_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (activity_sku_id, user_id),
    CONSTRAINT uq_purchase_claim_order UNIQUE (order_id),
    CONSTRAINT fk_claim_sku FOREIGN KEY (activity_sku_id) REFERENCES activity_sku_stock(id),
    CONSTRAINT fk_claim_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB;

CREATE TABLE inventory_reservation (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    activity_sku_id VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_reservation_order UNIQUE (order_id),
    CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_reservation_sku FOREIGN KEY (activity_sku_id) REFERENCES activity_sku_stock(id),
    CONSTRAINT ck_reservation_quantity CHECK (quantity = 1),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    INDEX idx_reservation_expiration (status, expires_at, id)
) ENGINE=InnoDB;

CREATE TABLE inventory_movement (
    id CHAR(36) NOT NULL,
    activity_sku_id VARCHAR(64) NOT NULL,
    order_id CHAR(36) NOT NULL,
    operation_id VARCHAR(160) NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    available_delta INT NOT NULL,
    reserved_delta INT NOT NULL,
    sold_delta INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_inventory_operation UNIQUE (operation_id),
    CONSTRAINT fk_movement_sku FOREIGN KEY (activity_sku_id) REFERENCES activity_sku_stock(id),
    CONSTRAINT fk_movement_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_movement_type CHECK (movement_type IN ('RESERVE', 'CONFIRM', 'RELEASE')),
    INDEX idx_movement_sku_created (activity_sku_id, created_at, id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
    operation_name VARCHAR(80) NOT NULL,
    caller_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_code VARCHAR(64),
    resource_id CHAR(36),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (operation_name, caller_id, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED'))
) ENGINE=InnoDB;

CREATE TABLE payment (
    id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    provider_transaction_id VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    apply_status VARCHAR(32) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    paid_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_payment_provider_transaction UNIQUE (provider_transaction_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_payment_status CHECK (status IN ('SUCCEEDED')),
    CONSTRAINT ck_payment_apply_status CHECK (apply_status IN ('APPLIED', 'REFUND_REQUIRED')),
    INDEX idx_payment_order (order_id)
) ENGINE=InnoDB;

CREATE TABLE payment_callback_event (
    provider_event_id VARCHAR(128) NOT NULL,
    provider_transaction_id VARCHAR(128) NOT NULL,
    order_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_code VARCHAR(64),
    payment_id CHAR(36),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (provider_event_id),
    CONSTRAINT fk_callback_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_callback_payment FOREIGN KEY (payment_id) REFERENCES payment(id),
    INDEX idx_callback_provider_tx (provider_transaction_id)
) ENGINE=InnoDB;

CREATE TABLE compensation_case (
    id CHAR(36) NOT NULL,
    case_type VARCHAR(64) NOT NULL,
    source_id CHAR(36) NOT NULL,
    order_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    details VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_compensation_source UNIQUE (case_type, source_id),
    CONSTRAINT fk_compensation_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_compensation_type CHECK (case_type IN ('LATE_PAYMENT_REFUND_REQUIRED')),
    CONSTRAINT ck_compensation_status CHECK (status IN ('OPEN', 'RESOLVED')),
    INDEX idx_compensation_status_created (status, created_at, id)
) ENGINE=InnoDB;
