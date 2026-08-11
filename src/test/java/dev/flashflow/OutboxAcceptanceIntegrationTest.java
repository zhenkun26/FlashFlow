package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.outbox.DurableOutboxAcceptanceService;
import dev.flashflow.messaging.outbox.OutboxAcceptanceTransactionHook;
import dev.flashflow.support.MySqlIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class OutboxAcceptanceIntegrationTest extends MySqlIntegrationTest {
    @Autowired private DurableOutboxAcceptanceService acceptance;
    @Autowired private CommandLedgerService ledger;
    @MockitoBean private OutboxAcceptanceTransactionHook hook;

    @AfterEach
    void clearHook() {
        reset(hook);
    }

    @Test
    void commandAndOutboxCommitAsOneAcceptedPairAndReplayDoesNotDuplicate() {
        OrderCommandEnvelope envelope = envelope("a", "b");
        acceptance.accept(envelope, "orders", "ORDER_V1");
        acceptance.replay(envelope, "orders", "ORDER_V1");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_outbox", Integer.class)).isEqualTo(1);
        assertThat(ledger.require(envelope.commandId()).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void persistenceFaultsExposeNoPartialAcceptedPair() {
        OrderCommandEnvelope afterCommand = envelope("c", "d");
        doThrow(new SimulatedFailure()).when(hook).afterCommandPrepared(afterCommand);
        assertThatThrownBy(() -> acceptance.accept(afterCommand, "orders", "ORDER_V1"))
                .isInstanceOf(SimulatedFailure.class);
        assertEmpty(afterCommand.commandId());

        reset(hook);
        OrderCommandEnvelope afterOutbox = envelope("e", "f");
        doThrow(new SimulatedFailure()).when(hook).afterOutboxPrepared(afterOutbox);
        assertThatThrownBy(() -> acceptance.accept(afterOutbox, "orders", "ORDER_V1"))
                .isInstanceOf(SimulatedFailure.class);
        assertEmpty(afterOutbox.commandId());
    }

    private void assertEmpty(String commandId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_command_ledger WHERE command_id = ?", Integer.class, commandId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_command_outbox WHERE command_id = ?", Integer.class, commandId))
                .isZero();
    }

    private static OrderCommandEnvelope envelope(String commandHex, String fingerprintHex) {
        return new OrderCommandEnvelope(1, commandHex.repeat(64), "user-" + commandHex, "sku",
                "key-" + commandHex, fingerprintHex.repeat(64), Instant.parse("2026-08-11T00:00:00Z"), "trace");
    }

    private static final class SimulatedFailure extends RuntimeException {
    }
}
