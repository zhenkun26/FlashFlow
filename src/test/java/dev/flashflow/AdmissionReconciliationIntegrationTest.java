package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionGenerationState;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionKeys;
import dev.flashflow.admission.RedisLuaAdmissionAdapter;
import dev.flashflow.admission.reconciliation.AdmissionReconciliationReport;
import dev.flashflow.admission.reconciliation.AdmissionReconciliationService;
import dev.flashflow.admission.reconciliation.ReconciliationStatus;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.RedisIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class AdmissionReconciliationIntegrationTest extends RedisIntegrationTest {
    @Autowired private AdmissionReconciliationService reconciliation;
    @Autowired private RedisLuaAdmissionAdapter admission;
    @Autowired private AdmissionIdentity identities;
    @Autowired private OrderApplicationService orders;
    @TempDir Path reports;

    @Test
    void replacesExcessCapacityAndDropsOrphanedConfirmationWithoutChangingMySql() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 5);
        AdmissionCommand orphan = command("s1", "orphan", "u-orphan");
        admission.acquire(orphan);
        admission.confirm(orphan, "g1");
        List<Integer> mysqlBefore = mysqlStock("s1");

        AdmissionReconciliationReport report = reconciliation.reconcile("s1", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.PASS);
        assertThat(report.discrepancies()).containsEntry("EXCESS_CAPACITY", 2L)
                .containsEntry("ORPHANED_CONFIRMED", 1L);
        assertThat(report.actions()).containsEntry("DROP_STALE", 1L);
        assertThat(admission.snapshot("s1").remainingCapacity()).isEqualTo(2);
        assertThat(admission.snapshot("s1").confirmed()).isZero();
        assertThat(mysqlStock("s1")).isEqualTo(mysqlBefore);
        assertThat(Files.list(reports)).hasSize(1);
    }

    @Test
    void restoresMissingConfirmationFromCommittedMySqlFacts() {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 2);
        OrderResult created = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        assertThat(created.code()).isEqualTo(OrderResultCode.CREATED);
        AdmissionKeys keys = new AdmissionKeys("s1");
        redis.delete(List.of(keys.admissions("g1"), keys.users("g1"), keys.deadlines("g1")));
        List<Integer> mysqlBefore = mysqlStock("s1");

        AdmissionReconciliationReport report = reconciliation.reconcile("s1", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.PASS);
        assertThat(report.discrepancies()).containsEntry("MISSING_CONFIRMATION", 1L);
        assertThat(report.actions()).containsEntry("SEED_CONFIRMED", 1L);
        assertThat(admission.snapshot("s1").remainingCapacity()).isEqualTo(1);
        assertThat(admission.snapshot("s1").confirmed()).isEqualTo(1);
        assertThat(mysqlStock("s1")).isEqualTo(mysqlBefore);
    }

    @Test
    void ambiguousHeldAdmissionWithholdsCapacityAndLeavesGenerationUnpublished() {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 2);
        admission.acquire(command("s1", "unresolved", "u1"));
        List<Integer> mysqlBefore = mysqlStock("s1");

        AdmissionReconciliationReport report = reconciliation.reconcile("s1", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.BLOCKED);
        assertThat(report.unresolved()).isEqualTo(1);
        assertThat(report.discrepancies()).containsEntry("AMBIGUOUS_HELD", 1L);
        assertThat(report.actions()).containsEntry("WITHHOLD_CAPACITY", 1L)
                .containsEntry("SEED_QUARANTINED", 1L);
        assertThat(admission.snapshot("s1").state()).isEqualTo(AdmissionGenerationState.INITIALIZING);
        assertThat(mysqlStock("s1")).isEqualTo(mysqlBefore);
    }

    @Test
    void restoresMissingCapacityAndDropsTerminalAndProvenNonEffectiveTokens() {
        fixture().activeSku("a1", "s1", 3);
        ready("s1", 1);
        AdmissionCommand terminal = command("s1", "released", "u2");
        admission.acquire(terminal);
        admission.release(terminal, "g1", false);
        AdmissionCommand proven = command("s1", "proven", "u1");
        admission.acquire(proven);
        jdbc.update("""
                INSERT INTO idempotency_record
                  (operation_name, caller_id, idempotency_key, request_hash, status,
                   result_code, created_at, completed_at)
                VALUES ('CREATE_ORDER', 'u1', 'proven', REPEAT('a', 64), 'COMPLETED',
                        'ACTIVITY_NOT_ACTIVE', NOW(6), NOW(6))
                """);
        List<Integer> mysqlBefore = mysqlStock("s1");

        AdmissionReconciliationReport report = reconciliation.reconcile("s1", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.PASS);
        assertThat(report.discrepancies()).containsEntry("MISSING_CAPACITY", 3L)
                .containsEntry("STALE_NON_EFFECTIVE", 1L);
        assertThat(report.actions()).containsEntry("DROP_TERMINAL", 1L)
                .containsEntry("RELEASE_PROVEN", 1L);
        assertThat(admission.snapshot("s1").remainingCapacity()).isEqualTo(3);
        assertThat(admission.snapshot("s1").held()).isZero();
        assertThat(mysqlStock("s1")).isEqualTo(mysqlBefore);
    }

    @Test
    void classifiesPreparedAmbiguousAndDeadLetteredCommandsWithoutAssumingBusinessTruth() {
        fixture().activeSku("a-command", "s-command", 3);
        ready("s-command", 3);
        insertCommandAdmission("s-command", "u-prepared", "k-prepared", "PREPARED", null, false);
        insertCommandAdmission("s-command", "u-ambiguous", "k-ambiguous", "UNRESOLVED", "TIMEOUT", false);
        insertCommandAdmission("s-command", "u-dlq", "k-dlq", "RETRYABLE", "RETRY_EXHAUSTED", true);

        AdmissionReconciliationReport report = reconciliation.reconcile("s-command", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.BLOCKED);
        assertThat(report.unresolved()).isEqualTo(3);
        assertThat(report.discrepancies()).containsEntry("AGED_PREPARED_COMMAND", 1L)
                .containsEntry("AMBIGUOUS_COMMAND", 1L)
                .containsEntry("DEAD_LETTERED_WITHOUT_MYSQL_RESULT", 1L);
        assertThat(report.actions()).containsEntry("SEED_QUARANTINED", 3L)
                .containsEntry("WITHHOLD_CAPACITY", 3L);
        assertThat(admission.snapshot("s-command").state()).isEqualTo(AdmissionGenerationState.INITIALIZING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE activity_sku_id = 's-command'",
                Integer.class)).isZero();
    }

    @Test
    void classifiesEveryOutboxDispositionWithoutReleasingAdmission() {
        fixture().activeSku("a-outbox", "s-outbox", 8);
        ready("s-outbox", 8);
        insertOutboxAdmission("s-outbox", "u-ready", "k-ready", "ACCEPTED", "READY");
        insertOutboxAdmission("s-outbox", "u-claimed", "k-claimed", "ACCEPTED", "CLAIMED");
        insertOutboxAdmission("s-outbox", "u-retry", "k-retry", "ACCEPTED", "RETRYABLE");
        insertOutboxAdmission("s-outbox", "u-ack", "k-ack", "ACCEPTED", "ACKNOWLEDGED");
        insertOutboxAdmission("s-outbox", "u-invalid", "k-invalid", "ACCEPTED", "INVALID");
        insertOutboxAdmission("s-outbox", "u-exhausted", "k-exhausted", "ACCEPTED", "EXHAUSTED");
        insertCommandAdmission("s-outbox", "u-missing", "k-missing", "ACCEPTED", "OUTBOX_COMMITTED", false);
        insertOutboxAdmission("s-outbox", "u-contradict", "k-contradict", "PREPARED", "READY");

        AdmissionReconciliationReport report = reconciliation.reconcile("s-outbox", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.BLOCKED);
        assertThat(report.unresolved()).isEqualTo(8);
        assertThat(report.discrepancies())
                .containsEntry("OUTBOX_READY_IN_FLIGHT", 1L)
                .containsEntry("OUTBOX_CLAIMED_IN_FLIGHT", 1L)
                .containsEntry("OUTBOX_RETRYABLE_IN_FLIGHT", 1L)
                .containsEntry("OUTBOX_ACKNOWLEDGED_AWAITING_RESULT", 1L)
                .containsEntry("OUTBOX_INVALID_OPERATOR_REQUIRED", 1L)
                .containsEntry("OUTBOX_EXHAUSTED_OPERATOR_REQUIRED", 1L)
                .containsEntry("MISSING_OUTBOX_FOR_ACCEPTED_COMMAND", 1L)
                .containsEntry("CONTRADICTORY_COMMAND_OUTBOX", 1L);
        assertThat(report.actions()).containsEntry("WITHHOLD_CAPACITY", 8L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE activity_sku_id = 's-outbox'",
                Integer.class)).isZero();
    }

    private void insertOutboxAdmission(
            String skuId, String userId, String key, String commandStatus, String outboxStatus) {
        insertCommandAdmission(skuId, userId, key, commandStatus, "OUTBOX_COMMITTED", false);
        String commandId = identities.admissionId("CREATE_ORDER", userId, key);
        jdbc.update("""
                INSERT INTO order_command_outbox
                  (outbox_id, command_id, schema_version, envelope_payload, envelope_fingerprint,
                   topic_name, tag_name, status, attempt_count, next_attempt_at,
                   lease_token, lease_owner, lease_until, result_code, acknowledged_at,
                   created_at, updated_at)
                VALUES (?, ?, 1, '{}', REPEAT('b', 64), 'orders', 'ORDER_V1', ?, 1, NOW(6),
                        CASE WHEN ? = 'CLAIMED' THEN ? ELSE NULL END,
                        CASE WHEN ? = 'CLAIMED' THEN 'test' ELSE NULL END,
                        CASE WHEN ? = 'CLAIMED' THEN DATE_ADD(NOW(6), INTERVAL 30 SECOND) ELSE NULL END,
                        CASE WHEN ? IN ('INVALID', 'EXHAUSTED') THEN ? ELSE NULL END,
                        CASE WHEN ? = 'ACKNOWLEDGED' THEN NOW(6) ELSE NULL END,
                        NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), commandId, outboxStatus, outboxStatus,
                UUID.randomUUID().toString(), outboxStatus, outboxStatus,
                outboxStatus, outboxStatus, outboxStatus);
    }

    private void insertCommandAdmission(
            String skuId, String userId, String key, String status, String cause, boolean deadLettered) {
        AdmissionCommand command = command(skuId, key, userId);
        admission.acquire(command);
        jdbc.update("""
                INSERT INTO order_command_ledger
                  (command_id, operation_name, caller_id, idempotency_key, activity_sku_id,
                   payload_fingerprint, schema_version, status, transport_cause,
                   created_at, updated_at, dead_lettered_at)
                VALUES (?, 'CREATE_ORDER', ?, ?, ?, REPEAT('a', 64), 1, ?, ?,
                        DATE_SUB(NOW(6), INTERVAL 1 MINUTE), NOW(6),
                        CASE WHEN ? THEN NOW(6) ELSE NULL END)
                """, command.admissionId(), userId, key, skuId, status, cause, deadLettered);
    }

    private AdmissionCommand command(String skuId, String idempotencyKey, String userId) {
        return new AdmissionCommand(skuId,
                identities.admissionId("CREATE_ORDER", userId, idempotencyKey),
                identities.userDigest(skuId, userId), Instant.now().plusSeconds(30));
    }

    private void ready(String skuId, int capacity) {
        String fence = UUID.randomUUID().toString();
        assertThat(admission.beginGeneration(skuId, "g1", capacity, fence)).isTrue();
        assertThat(admission.publishGeneration(skuId, "g1", fence)).isTrue();
    }

    private List<Integer> mysqlStock(String skuId) {
        return jdbc.queryForObject("""
                SELECT initial_stock, available_stock, reserved_stock, sold_stock
                FROM activity_sku_stock WHERE id = ?
                """, (result, row) -> List.of(result.getInt(1), result.getInt(2),
                        result.getInt(3), result.getInt(4)), skuId);
    }
}
