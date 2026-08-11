package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.messaging.CommandConflictException;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.outbox.OutboxStatus;
import dev.flashflow.messaging.outbox.OutboxStore;
import dev.flashflow.messaging.persistence.OutboxMapper;
import dev.flashflow.messaging.persistence.OutboxRow;
import dev.flashflow.support.MySqlIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxPersistenceIntegrationTest extends MySqlIntegrationTest {
    @Autowired private CommandLedgerService ledger;
    @Autowired private OutboxStore outbox;
    @Autowired private OutboxMapper mapper;

    @Test
    void immutableReplayConvergesOnOneOutboxAndConflictingRoutingIsRejected() {
        OrderCommandEnvelope envelope = envelope("a", "b");
        ledger.prepare(envelope);

        OutboxRow first = outbox.prepare(envelope, "orders", "create");
        OutboxRow replay = outbox.prepare(envelope, "orders", "create");

        assertThat(replay.outboxId()).isEqualTo(first.outboxId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_outbox", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> outbox.prepare(envelope, "other-orders", "create"))
                .isInstanceOf(CommandConflictException.class);
    }

    @Test
    void leaseTokenFencesTransitionsAndTerminalRowsAreNotReclaimed() {
        OrderCommandEnvelope envelope = envelope("c", "d");
        ledger.prepare(envelope);
        outbox.prepare(envelope, "orders", "create");

        OutboxRow claimed = outbox.claimBatch(1, "dispatcher-1", Duration.ofSeconds(30)).getFirst().row();
        OutboxRow stale = new OutboxRow(claimed.outboxId(), claimed.commandId(), claimed.schemaVersion(),
                claimed.envelopePayload(), claimed.envelopeFingerprint(), claimed.topicName(), claimed.tagName(),
                claimed.status(), claimed.attemptCount(), claimed.nextAttemptAt(), "0".repeat(36),
                claimed.leaseOwner(), claimed.leaseUntil(), claimed.resultCode(), claimed.acknowledgedAt(),
                claimed.createdAt(), claimed.updatedAt());

        assertThat(outbox.acknowledge(stale, "SEND_OK")).isFalse();
        assertThat(outbox.acknowledge(claimed, "SEND_OK")).isTrue();
        assertThat(outbox.claimBatch(1, "dispatcher-2", Duration.ofSeconds(30))).isEmpty();
        assertThat(mapper.findByCommandId(envelope.commandId()).status())
                .isEqualTo(OutboxStatus.ACKNOWLEDGED.name());
    }

    @Test
    void preparedAndUnresolvedV3RowsAreNotBackfilledOrClaimed() {
        OrderCommandEnvelope prepared = envelope("e", "f");
        OrderCommandEnvelope unresolved = envelope("1", "2");
        ledger.prepare(prepared);
        ledger.prepare(unresolved);
        jdbc.update("UPDATE order_command_ledger SET status = 'UNRESOLVED' WHERE command_id = ?",
                unresolved.commandId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_outbox", Integer.class)).isZero();
        assertThat(outbox.claimBatch(10, "dispatcher", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void databaseRejectsBrokenLeaseAndStatusConstraints() {
        OrderCommandEnvelope envelope = envelope("3", "4");
        ledger.prepare(envelope);
        OutboxRow row = outbox.prepare(envelope, "orders", "create");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE order_command_outbox
                SET status = 'CLAIMED', lease_token = NULL, lease_owner = NULL, lease_until = NULL
                WHERE outbox_id = ?
                """, row.outboxId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE order_command_outbox SET attempt_count = -1 WHERE outbox_id = ?", row.outboxId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void concurrentDispatchersHoldOneActiveLeaseAndExpiredOwnershipIsRecovered() throws Exception {
        OrderCommandEnvelope envelope = envelope("5", "6");
        ledger.prepare(envelope);
        outbox.prepare(envelope, "orders", "ORDER_V1");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> outbox.claimBatch(1, "left", Duration.ofSeconds(30)));
            var right = executor.submit(() -> outbox.claimBatch(1, "right", Duration.ofSeconds(30)));
            int claims = left.get(10, TimeUnit.SECONDS).size() + right.get(10, TimeUnit.SECONDS).size();
            assertThat(claims).isEqualTo(1);
        }

        OutboxRow active = mapper.findByCommandId(envelope.commandId());
        assertThat(outbox.claimBatch(1, "blocked", Duration.ofSeconds(30))).isEmpty();
        jdbc.update("UPDATE order_command_outbox SET lease_until = ? WHERE outbox_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), active.outboxId());
        var takeover = outbox.claimBatch(1, "successor", Duration.ofSeconds(30));
        assertThat(takeover).hasSize(1);
        assertThat(takeover.getFirst().leaseTakeover()).isTrue();
        assertThat(takeover.getFirst().row().leaseToken()).isNotEqualTo(active.leaseToken());
    }

    @Test
    void cleanupEligibilityIncludesOnlyRetainedAcknowledgedTerminalRows() {
        OrderCommandEnvelope terminal = envelope("7", "8");
        ledger.prepare(terminal);
        outbox.prepare(terminal, "orders", "ORDER_V1");
        OutboxRow claimed = outbox.claimBatch(1, "cleanup", Duration.ofSeconds(30)).getFirst().row();
        assertThat(outbox.acknowledge(claimed, "SEND_OK")).isTrue();
        assertThat(ledger.claim(terminal.commandId())).isTrue();
        ledger.finish(terminal.commandId(), CommandStatus.REJECTED, "SOLD_OUT", null);
        jdbc.update("UPDATE order_command_outbox SET acknowledged_at = DATE_SUB(NOW(6), INTERVAL 8 DAY)");

        assertThat(outbox.cleanupCandidates(Duration.ofDays(7), 10))
                .extracting(OutboxRow::commandId).containsExactly(terminal.commandId());

        jdbc.update("UPDATE order_command_ledger SET status = 'ACCEPTED' WHERE command_id = ?",
                terminal.commandId());
        assertThat(outbox.cleanupCandidates(Duration.ofDays(7), 10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_outbox", Integer.class)).isEqualTo(1);
    }

    private static OrderCommandEnvelope envelope(String commandHex, String fingerprintHex) {
        return new OrderCommandEnvelope(1, commandHex.repeat(64), "user-" + commandHex, "sku",
                "key-" + commandHex, fingerprintHex.repeat(64), Instant.parse("2026-08-11T00:00:00Z"), "trace");
    }
}
