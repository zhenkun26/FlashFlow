package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.support.MySqlIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class MigrationConstraintTest extends MySqlIntegrationTest {
    @Test
    void requiredIndexesAndConstraintsExist() {
        fixture().activeSku("a1", "s1", 2);
        Integer expirationIndex = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'orders'
                  AND index_name = 'idx_order_expiration'
                """, Integer.class);
        Integer stockChecks = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE() AND table_name = 'activity_sku_stock'
                  AND constraint_type = 'CHECK'
                """, Integer.class);
        Integer outboxEligibilityIndex = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'order_command_outbox'
                  AND index_name = 'idx_outbox_dispatch_eligibility'
                """, Integer.class);
        Integer outboxForeignKey = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE() AND table_name = 'order_command_outbox'
                  AND referenced_table_name = 'order_command_ledger'
                """, Integer.class);
        assertThat(expirationIndex).isPositive();
        assertThat(stockChecks).isGreaterThanOrEqualTo(2);
        assertThat(outboxEligibilityIndex).isPositive();
        assertThat(outboxForeignKey).isEqualTo(1);
    }

    @Test
    void databaseRejectsBrokenStockConservation() {
        fixture().activity("a1", "ENABLED", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(1));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO activity_sku_stock
                    (id, activity_id, sku_code, unit_price, currency, initial_stock,
                     available_stock, reserved_stock, sold_stock, created_at, updated_at)
                VALUES ('s1', 'a1', 'sku', 10.00, 'CNY', 10, 9, 0, 0, NOW(6), NOW(6))
                """))
                .isInstanceOf(DataAccessException.class);
    }
}
