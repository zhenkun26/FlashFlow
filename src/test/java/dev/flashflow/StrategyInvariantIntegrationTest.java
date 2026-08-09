package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.inventory.InventoryStrategyRegistry;
import dev.flashflow.inventory.InventoryReservationStrategy;
import dev.flashflow.inventory.ReservationAttempt;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.NoOpOrderingTransactionHook;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.support.MySqlIntegrationTest;
import dev.flashflow.verification.InvariantService;
import dev.flashflow.verification.persistence.InvariantMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class StrategyInvariantIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderMapper orderMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private InventoryStrategyRegistry registry;
    @Autowired private FlashFlowMetrics metrics;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private InvariantMapper invariantMapper;
    @Autowired private MeterRegistry meterRegistry;

    @ParameterizedTest
    @EnumSource(value = FlashFlowProperties.Strategy.class,
            names = {"CONDITIONAL_ATOMIC", "PESSIMISTIC", "OPTIMISTIC"})
    void safeStrategiesPreserveInvariantsUnderExcessDemand(FlashFlowProperties.Strategy strategy) throws Exception {
        fixture().activeSku("a1", "s1", 5);
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(strategy, 20),
                new FlashFlowProperties.Ordering(20, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false));
        OrderApplicationService service = new OrderApplicationService(orderMapper, inventoryMapper, registry,
                properties, Clock.systemUTC(), metrics, transactionManager, new NoOpOrderingTransactionHook());
        int concurrency = 25;
        CountDownLatch start = new CountDownLatch(1);

        List<OrderResultCode> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, concurrency)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return service.place(new PlaceOrderCommand("u" + index, "s1", "k" + index)).code();
                    })).toList();
            start.countDown();
            results = futures.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results).filteredOn(code -> code == OrderResultCode.CREATED).hasSize(5);
        new InvariantService(invariantMapper).requireValid();
    }

    @Test
    void deterministicOptimisticConflictExhaustsBudgetWithoutPartialEffects() {
        fixture().activeSku("a1", "s1", 1);
        double attemptsBefore = attempt("OPTIMISTIC", "STARTED");
        double conflictsBefore = attempt("OPTIMISTIC", "OPTIMISTIC_CONFLICT");
        double exhaustedBefore = attempt("OPTIMISTIC", "RETRY_EXHAUSTED");
        double outcomeBefore = outcome("RETRYABLE_CONTENTION");
        InventoryReservationStrategy alwaysConflicts = new InventoryReservationStrategy() {
            @Override
            public FlashFlowProperties.Strategy kind() {
                return FlashFlowProperties.Strategy.OPTIMISTIC;
            }

            @Override
            public ReservationAttempt reserve(String skuId, LocalDateTime now) {
                return ReservationAttempt.CONFLICT;
            }
        };
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.OPTIMISTIC, 2),
                new FlashFlowProperties.Ordering(0, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false));
        OrderApplicationService service = new OrderApplicationService(orderMapper, inventoryMapper,
                new InventoryStrategyRegistry(List.of(alwaysConflicts)), properties,
                Clock.systemUTC(), metrics, transactionManager, new NoOpOrderingTransactionHook());

        assertThat(service.place(new PlaceOrderCommand("u1", "s1", "k1")).code())
                .isEqualTo(OrderResultCode.RETRYABLE_CONTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
        assertThat(attempt("OPTIMISTIC", "STARTED") - attemptsBefore).isEqualTo(3);
        assertThat(attempt("OPTIMISTIC", "OPTIMISTIC_CONFLICT") - conflictsBefore).isEqualTo(3);
        assertThat(attempt("OPTIMISTIC", "RETRY_EXHAUSTED") - exhaustedBefore).isEqualTo(1);
        assertThat(outcome("RETRYABLE_CONTENTION") - outcomeBefore).isEqualTo(1);
        new InvariantService(invariantMapper).requireValid();
    }

    @Test
    void unexpectedStrategyFailureIsClassifiedAndRollsBack() {
        fixture().activeSku("a1", "s1", 1);
        InventoryReservationStrategy broken = new InventoryReservationStrategy() {
            @Override
            public FlashFlowProperties.Strategy kind() {
                return FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC;
            }

            @Override
            public ReservationAttempt reserve(String skuId, LocalDateTime now) {
                throw new IllegalStateException("injected unexpected failure");
            }
        };
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 0),
                new FlashFlowProperties.Ordering(0, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false));
        OrderApplicationService service = new OrderApplicationService(orderMapper, inventoryMapper,
                new InventoryStrategyRegistry(List.of(broken)), properties,
                Clock.systemUTC(), metrics, transactionManager, new NoOpOrderingTransactionHook());
        double unexpectedBefore = outcome("UNEXPECTED_FAILURE");

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand("u1", "s1", "k1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected unexpected failure");
        assertThat(outcome("UNEXPECTED_FAILURE") - unexpectedBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isZero();
        new InvariantService(invariantMapper).requireValid();
    }

    private double attempt(String strategy, String outcome) {
        var counter = meterRegistry.find("flashflow.order.attempt")
                .tags("strategy", strategy, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double outcome(String code) {
        var counter = meterRegistry.find("flashflow.order.outcome").tag("code", code).counter();
        return counter == null ? 0 : counter.count();
    }
}
