INSERT INTO activity(id, name, status, starts_at, ends_at, created_at)
VALUES ('demo-activity', 'FlashFlow Demo', 'ENABLED',
        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR),
        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status = VALUES(status), starts_at = VALUES(starts_at), ends_at = VALUES(ends_at);

INSERT INTO activity_sku_stock(
    id, activity_id, sku_code, unit_price, currency,
    initial_stock, available_stock, reserved_stock, sold_stock, version, created_at, updated_at)
VALUES ('demo-sku', 'demo-activity', 'DEMO-SKU', 99.00, 'CNY',
        1000, 1000, 0, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
