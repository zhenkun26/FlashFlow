package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.inventory.InventoryStrategyRegistry;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.ordering.NoOpOrderingTransactionHook;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.OrderingTransactionHook;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.support.MySqlIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class OrderingClaimRaceIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderMapper orderMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private InventoryStrategyRegistry registry;
    @Autowired private FlashFlowMetrics metrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void postReservationClaimLossRollsBackAndReplaysExistingOrder() {
        fixture().activeSku("a1", "s1", 2);
        OrderApplicationService winner = service(1, new NoOpOrderingTransactionHook());
        AtomicBoolean injected = new AtomicBoolean();
        OrderingTransactionHook hook = command -> {
            if (!injected.compareAndSet(false, true)) return;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                OrderResult result = executor.submit(() -> winner.place(
                                new PlaceOrderCommand(command.userId(), command.activitySkuId(), "winner-key")))
                        .get(10, TimeUnit.SECONDS);
                assertThat(result.code()).isEqualTo(OrderResultCode.CREATED);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
        double rollbacksBefore = attempt("CLAIM_RACE_ROLLBACK");
        double replaysBefore = attempt("CLAIM_RACE_REPLAY");

        OrderResult result = service(1, hook).place(new PlaceOrderCommand("u1", "s1", "loser-key"));

        assertThat(result.code()).isEqualTo(OrderResultCode.EXISTING_EFFECTIVE_ORDER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
        assertThat(attempt("CLAIM_RACE_ROLLBACK") - rollbacksBefore).isEqualTo(1);
        assertThat(attempt("CLAIM_RACE_REPLAY") - replaysBefore).isEqualTo(1);
    }

    @Test
    void zeroClaimRaceBudgetReturnsRetryableWithoutPartialLoserEffect() {
        fixture().activeSku("a1", "s1", 2);
        OrderApplicationService winner = service(1, new NoOpOrderingTransactionHook());
        AtomicBoolean injected = new AtomicBoolean();
        OrderingTransactionHook hook = command -> {
            if (!injected.compareAndSet(false, true)) return;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> winner.place(
                                new PlaceOrderCommand(command.userId(), command.activitySkuId(), "winner-key")))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
        double exhaustedBefore = attempt("CLAIM_RACE_EXHAUSTED");

        OrderResult result = service(0, hook).place(new PlaceOrderCommand("u1", "s1", "loser-key"));

        assertThat(result.code()).isEqualTo(OrderResultCode.RETRYABLE_CONTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
        assertThat(attempt("CLAIM_RACE_EXHAUSTED") - exhaustedBefore).isEqualTo(1);
    }

    @Test
    void soldOutRecheckPreservesExistingOrderPrecedence() {
        fixture().activeSku("a1", "s1", 1);
        OrderApplicationService winner = service(1, new NoOpOrderingTransactionHook());
        AtomicBoolean injected = new AtomicBoolean();
        OrderingTransactionHook hook = command -> {
            if (!injected.compareAndSet(false, true)) return;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> winner.place(
                                new PlaceOrderCommand(command.userId(), command.activitySkuId(), "winner-key")))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };

        OrderResult result = service(1, hook).place(new PlaceOrderCommand("u1", "s1", "loser-key"));

        assertThat(result.code()).isEqualTo(OrderResultCode.EXISTING_EFFECTIVE_ORDER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
    }

    private OrderApplicationService service(int claimRaceRetries, OrderingTransactionHook hook) {
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(
                        3, claimRaceRetries, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false));
        return new OrderApplicationService(orderMapper, inventoryMapper, registry, properties,
                Clock.systemUTC(), metrics, transactionManager, hook);
    }

    private double attempt(String outcome) {
        var counter = meterRegistry.find("flashflow.order.attempt")
                .tags("strategy", "CONDITIONAL_ATOMIC", "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
