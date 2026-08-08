package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.flashflow.inventory.ReservationAttempt;
import dev.flashflow.inventory.UnsafeInterleavingHook;
import dev.flashflow.inventory.UnsafeReadThenWriteStrategy;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.StockRow;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UnsafeInterleavingTest {
    @Test
    void deterministicBarrierReproducesLostUpdate() throws Exception {
        InventoryMapper mapper = mock(InventoryMapper.class);
        AtomicInteger available = new AtomicInteger(1);
        AtomicInteger reserved = new AtomicInteger(0);
        when(mapper.findStock("s1")).thenAnswer(ignored ->
                new StockRow("s1", 1, available.get(), reserved.get(), 0, 0));
        when(mapper.overwriteUnsafe(eq("s1"), anyInt(), anyInt(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    available.set(invocation.getArgument(1));
                    reserved.set(invocation.getArgument(2));
                    return 1;
                });

        CountDownLatch bothRead = new CountDownLatch(2);
        UnsafeInterleavingHook barrier = observed -> {
            bothRead.countDown();
            try {
                if (!bothRead.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Both unsafe transactions did not reach the read barrier");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        UnsafeReadThenWriteStrategy strategy = new UnsafeReadThenWriteStrategy(mapper, barrier);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ReservationAttempt> one = executor.submit(() -> strategy.reserve("s1", LocalDateTime.now()));
            Future<ReservationAttempt> two = executor.submit(() -> strategy.reserve("s1", LocalDateTime.now()));
            assertThat(one.get()).isEqualTo(ReservationAttempt.RESERVED);
            assertThat(two.get()).isEqualTo(ReservationAttempt.RESERVED);
        }
        assertThat(available).hasValue(0);
        assertThat(reserved).hasValue(1);
    }
}

