package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrentIdempotencyIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderApplicationService orders;

    @Test
    void sameKeyAndSameUserProduceOneBusinessEffect() throws Exception {
        fixture().activeSku("a1", "s1", 10);
        List<OrderResult> results = concurrent(20, index ->
                orders.place(new PlaceOrderCommand("u1", "s1", "shared-key")));
        assertThat(results).extracting(OrderResult::orderId).doesNotContainNull().containsOnly(results.getFirst().orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameUserDifferentKeysStillHasOneEffectiveOrder() throws Exception {
        fixture().activeSku("a1", "s1", 10);
        concurrent(20, index -> orders.place(new PlaceOrderCommand("u1", "s1", "key-" + index)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
    }

    private static List<OrderResult> concurrent(int count, ThrowingIntFunction operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return operation.apply(index);
                    })).toList();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntFunction {
        OrderResult apply(int index) throws Exception;
    }
}

