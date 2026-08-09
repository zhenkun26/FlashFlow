package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.messaging.CommandConflictException;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.persistence.CommandMapper;
import dev.flashflow.support.MySqlIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommandLedgerIntegrationTest extends MySqlIntegrationTest {
    @Autowired private CommandLedgerService ledger;
    @Autowired private CommandMapper mapper;

    @Test
    void concurrentPrepareAndClaimConvergesAndTerminalStateIsFenced() throws Exception {
        OrderCommandEnvelope envelope = envelope("a", "user", "key", "sku", "b");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> ledger.prepare(envelope));
            var right = executor.submit(() -> ledger.prepare(envelope));
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_ledger", Integer.class)).isEqualTo(1);
        assertThat(ledger.claim(envelope.commandId())).isTrue();
        ledger.finish(envelope.commandId(), CommandStatus.REJECTED, "SOLD_OUT", null);
        assertThat(mapper.markNonTerminal(envelope.commandId(), "UNRESOLVED",
                LocalDateTime.now(ZoneOffset.UTC))).isZero();
        assertThat(ledger.summary(envelope.commandId()).status()).isEqualTo(CommandStatus.REJECTED);
    }

    @Test
    void conflictingPayloadCannotReuseCommandIdentity() {
        OrderCommandEnvelope original = envelope("a", "user", "key", "sku", "b");
        ledger.prepare(original);
        OrderCommandEnvelope conflict = envelope("a", "user", "key", "other-sku", "c");
        assertThatThrownBy(() -> ledger.prepare(conflict)).isInstanceOf(CommandConflictException.class);
    }

    private static OrderCommandEnvelope envelope(
            String commandHex, String caller, String key, String sku, String fingerprintHex) {
        return new OrderCommandEnvelope(1, commandHex.repeat(64), caller, sku, key,
                fingerprintHex.repeat(64), Instant.parse("2026-08-09T00:00:00Z"), "trace");
    }
}
