package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.messaging.CommandConsumptionResult;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.InProcessOrderCommandConsumer;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.OrderCommandExecutor;
import dev.flashflow.messaging.OrderCommandFactory;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.support.MySqlIntegrationTest;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderCommandConsumerIntegrationTest extends MySqlIntegrationTest {
    @Autowired private InProcessOrderCommandConsumer consumer;
    @Autowired private OrderCommandFactory factory;
    @Autowired private CommandLedgerService ledger;
    @Autowired private OrderApplicationService ordering;
    @Autowired private FlashFlowMetrics metrics;

    @Test
    void sequentialAndConcurrentDuplicatesCreateOneDurableEffect() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        OrderCommandEnvelope envelope = factory.create(new PlaceOrderCommand("u1", "s1", "key"), "trace");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> consumer.consume(envelope));
            var right = executor.submit(() -> consumer.consume(envelope));
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        }
        CommandConsumptionResult replay = consumer.consume(envelope);
        assertThat(replay.acknowledgementEligible()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isEqualTo(1);
    }

    @Test
    void interruptionBeforeBusinessExecutionIsRetryable() {
        fixture().activeSku("a1", "s1", 1);
        OrderCommandEnvelope envelope = factory.create(new PlaceOrderCommand("u1", "s1", "key"), "trace");
        InProcessOrderCommandConsumer failing = consumer(command -> {
            throw new IllegalStateException("before commit");
        });
        assertThatThrownBy(() -> failing.consume(envelope)).hasMessage("before commit");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(consumer.consume(envelope).status()).isEqualTo(CommandStatus.COMPLETED);
    }

    @Test
    void interruptionAfterCommitBeforeAckReplaysCommittedResult() {
        fixture().activeSku("a1", "s1", 1);
        OrderCommandEnvelope envelope = factory.create(new PlaceOrderCommand("u1", "s1", "key"), "trace");
        InProcessOrderCommandConsumer lostAck = consumer(command -> {
            var result = ordering.place(command);
            throw new LostAck(result.orderId());
        });
        assertThatThrownBy(() -> lostAck.consume(envelope)).isInstanceOf(LostAck.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(consumer.consume(envelope).status()).isEqualTo(CommandStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void synchronousAndCommandPathsConverge() throws Exception {
        fixture().activeSku("a1", "s1", 1);
        PlaceOrderCommand command = new PlaceOrderCommand("u1", "s1", "key");
        OrderCommandEnvelope envelope = factory.create(command, "trace");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sync = executor.submit(() -> ordering.place(command));
            var async = executor.submit(() -> consumer.consume(envelope));
            sync.get(10, TimeUnit.SECONDS);
            async.get(10, TimeUnit.SECONDS);
        }
        assertThat(consumer.consume(envelope).status()).isEqualTo(CommandStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
    }

    private InProcessOrderCommandConsumer consumer(OrderCommandExecutor executor) {
        return new InProcessOrderCommandConsumer(ledger, executor, metrics);
    }

    private static final class LostAck extends RuntimeException {
        private LostAck(String orderId) { super("lost acknowledgement after order " + orderId); }
    }
}
