package dev.flashflow.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseFixture {
    private final JdbcTemplate jdbc;

    public DatabaseFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.update("DELETE FROM order_command_ledger");
        jdbc.update("DELETE FROM compensation_case");
        jdbc.update("DELETE FROM payment_callback_event");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM inventory_movement");
        jdbc.update("DELETE FROM inventory_reservation");
        jdbc.update("DELETE FROM purchase_claim");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM activity_sku_stock");
        jdbc.update("DELETE FROM activity");
    }

    public void activeSku(String activityId, String skuId, int stock) {
        LocalDateTime now = nowUtc();
        activity(activityId, "ENABLED", now.minusHours(1), now.plusHours(1));
        sku(activityId, skuId, stock);
    }

    public void inactiveSku(String activityId, String skuId, int stock) {
        LocalDateTime now = nowUtc();
        activity(activityId, "DISABLED", now.minusHours(1), now.plusHours(1));
        sku(activityId, skuId, stock);
    }

    public void activity(String id, String status, LocalDateTime startsAt, LocalDateTime endsAt) {
        jdbc.update("""
                INSERT INTO activity(id, name, status, starts_at, ends_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, "Activity " + id, status, startsAt, endsAt, nowUtc());
    }

    public void sku(String activityId, String skuId, int stock) {
        jdbc.update("""
                INSERT INTO activity_sku_stock
                    (id, activity_id, sku_code, unit_price, currency, initial_stock,
                     available_stock, reserved_stock, sold_stock, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'CNY', ?, ?, 0, 0, 0, ?, ?)
                """, skuId, activityId, "SKU-" + skuId, new BigDecimal("99.00"), stock, stock,
                nowUtc(), nowUtc());
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
