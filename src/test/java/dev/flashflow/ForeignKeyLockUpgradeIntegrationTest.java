package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.support.MySqlIntegrationTest;
import dev.flashflow.verification.InvariantService;
import dev.flashflow.verification.persistence.InvariantMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ForeignKeyLockUpgradeIntegrationTest extends MySqlIntegrationTest {
    @Autowired private InvariantMapper invariantMapper;

    @Test
    void childRowsBeforeStockReproduceSharedToExclusiveUpgradeDeadlock() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            insertOrder(first, "old-1", "u1");
            insertOrder(second, "old-2", "u2");

            assertThat(stockLockModes())
                    .hasSizeGreaterThanOrEqualTo(2)
                    .allMatch(mode -> mode.equals("S") || mode.startsWith("S,"));

            CountDownLatch update = new CountDownLatch(1);
            List<SqlOutcome> outcomes;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var one = executor.submit(() -> updateStock(first, update));
                var two = executor.submit(() -> updateStock(second, update));
                update.countDown();
                outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(SqlOutcome::deadlock).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.deadlock()).hasSize(1);
            first.rollback();
            second.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void stockFirstTransactionsCompleteAndPreserveCommittedInvariants() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        CountDownLatch start = new CountDownLatch(1);
        List<SqlOutcome> outcomes;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> stockFirst("new-1", "u1", start));
            var two = executor.submit(() -> stockFirst("new-2", "u2", start));
            start.countDown();
            outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes).allMatch(outcome -> !outcome.deadlock() && outcome.updated() == 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isEqualTo(2);
        new InvariantService(invariantMapper).requireValid();
    }

    private SqlOutcome stockFirst(String orderId, String userId, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            int updated = reserve(connection);
            insertOrder(connection, orderId, userId);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            try (var claim = connection.prepareStatement("""
                    INSERT INTO purchase_claim(activity_sku_id,user_id,order_id,created_at)
                    VALUES ('s1',?,?,?)
                    """)) {
                claim.setString(1, userId);
                claim.setString(2, orderId);
                claim.setTimestamp(3, Timestamp.valueOf(now));
                claim.executeUpdate();
            }
            try (var reservation = connection.prepareStatement("""
                    INSERT INTO inventory_reservation
                    (id,order_id,activity_sku_id,quantity,status,expires_at,created_at,updated_at)
                    VALUES (?,?, 's1',1,'RESERVED',?,?,?)
                    """)) {
                reservation.setString(1, UUID.randomUUID().toString());
                reservation.setString(2, orderId);
                reservation.setTimestamp(3, Timestamp.valueOf(now.plusMinutes(10)));
                reservation.setTimestamp(4, Timestamp.valueOf(now));
                reservation.setTimestamp(5, Timestamp.valueOf(now));
                reservation.executeUpdate();
            }
            try (var movement = connection.prepareStatement("""
                    INSERT INTO inventory_movement
                    (id,activity_sku_id,order_id,operation_id,movement_type,available_delta,reserved_delta,sold_delta,created_at)
                    VALUES (?,'s1',?,?,'RESERVE',-1,1,0,?)
                    """)) {
                movement.setString(1, UUID.randomUUID().toString());
                movement.setString(2, orderId);
                movement.setString(3, "reserve:" + orderId);
                movement.setTimestamp(4, Timestamp.valueOf(now));
                movement.executeUpdate();
            }
            connection.commit();
            return new SqlOutcome(updated, false);
        } catch (SQLException exception) {
            return new SqlOutcome(0, isDeadlock(exception));
        }
    }

    private SqlOutcome updateStock(Connection connection, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            return new SqlOutcome(reserve(connection), false);
        } catch (SQLException exception) {
            return new SqlOutcome(0, isDeadlock(exception));
        }
    }

    private static boolean isDeadlock(SQLException exception) {
        return exception.getErrorCode() == 1213 || "40001".equals(exception.getSQLState());
    }

    private static int reserve(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE activity_sku_stock
                SET available_stock=available_stock-1,reserved_stock=reserved_stock+1,version=version+1
                WHERE id='s1' AND available_stock>=1
                """)) {
            return statement.executeUpdate();
        }
    }

    private static void insertOrder(Connection connection, String orderId, String userId) throws SQLException {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try (var statement = connection.prepareStatement("""
                INSERT INTO orders
                (id,user_id,activity_sku_id,status,unit_price,currency,expires_at,created_at,updated_at)
                VALUES (?,?,'s1','PENDING_PAYMENT',99.00,'CNY',?,?,?)
                """)) {
            statement.setString(1, orderId);
            statement.setString(2, userId);
            statement.setTimestamp(3, Timestamp.valueOf(now.plusMinutes(10)));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            statement.setTimestamp(5, Timestamp.valueOf(now));
            statement.executeUpdate();
        }
    }

    private List<String> stockLockModes() throws SQLException {
        try {
            String rootPassword = MYSQL.getEnvMap().get("MYSQL_ROOT_PASSWORD");
            var result = MYSQL.execInContainer("mysql", "-uroot", "-p" + rootPassword, "-N", "-B", "-e", """
                    SELECT LOCK_MODE FROM performance_schema.data_locks
                    WHERE OBJECT_SCHEMA='flashflow' AND OBJECT_NAME='activity_sku_stock'
                      AND INDEX_NAME='PRIMARY' AND LOCK_TYPE='RECORD';
                    """);
            if (result.getExitCode() != 0) throw new SQLException(result.getStderr());
            return result.getStdout().lines().filter(value -> !value.isBlank()).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while inspecting MySQL locks", exception);
        } catch (java.io.IOException exception) {
            throw new SQLException("Unable to inspect MySQL locks", exception);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private record SqlOutcome(int updated, boolean deadlock) {
    }
}
