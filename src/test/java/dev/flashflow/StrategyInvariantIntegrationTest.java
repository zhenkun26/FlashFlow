package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.inventory.InventoryStrategyRegistry;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.ordering.OrderApplicationService;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
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

    @ParameterizedTest
    @EnumSource(value = FlashFlowProperties.Strategy.class,
            names = {"CONDITIONAL_ATOMIC", "PESSIMISTIC", "OPTIMISTIC"})
    void safeStrategiesPreserveInvariantsUnderExcessDemand(FlashFlowProperties.Strategy strategy) throws Exception {
        fixture().activeSku("a1", "s1", 5);
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(strategy, 20),
                new FlashFlowProperties.Ordering(20),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false));
        OrderApplicationService service = new OrderApplicationService(orderMapper, inventoryMapper, registry,
                properties, Clock.systemUTC(), metrics, transactionManager);
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
}
