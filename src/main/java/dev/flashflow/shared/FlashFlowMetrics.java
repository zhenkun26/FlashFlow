package dev.flashflow.shared;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public final class FlashFlowMetrics {
    private final MeterRegistry registry;
    private final Timer orderTimer;

    public FlashFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.orderTimer = registry.timer("flashflow.order.transaction");
    }

    public <T> T timeOrder(Supplier<T> operation) {
        return orderTimer.record(operation);
    }

    public void orderOutcome(String code) {
        Counter.builder("flashflow.order.outcome").tag("code", code).register(registry).increment();
    }

    public void idempotencyHit() {
        registry.counter("flashflow.idempotency.hit").increment();
    }

    public void strategyConflict(String strategy) {
        Counter.builder("flashflow.inventory.conflict").tag("strategy", strategy).register(registry).increment();
    }

    public void paymentOutcome(String code) {
        Counter.builder("flashflow.payment.outcome").tag("code", code).register(registry).increment();
    }

    public void expirationOutcome(String code) {
        Counter.builder("flashflow.expiration.outcome").tag("code", code).register(registry).increment();
    }
}
