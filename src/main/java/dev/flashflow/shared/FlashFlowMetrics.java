package dev.flashflow.shared;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public final class FlashFlowMetrics {
    private final MeterRegistry registry;
    private final Timer orderTimer;
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicLong outboxOldestAgeMillis = new AtomicLong();
    private final AtomicLong outboxUnresolved = new AtomicLong();

    public FlashFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.orderTimer = registry.timer("flashflow.order.transaction");
        registry.gauge("flashflow.messaging.outbox.backlog", outboxBacklog);
        registry.gauge("flashflow.messaging.outbox.oldest.age.millis", outboxOldestAgeMillis);
        registry.gauge("flashflow.messaging.outbox.unresolved", outboxUnresolved);
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

    public void orderAttempt(String strategy) {
        orderAttemptOutcome(strategy, OrderAttemptOutcome.STARTED);
    }

    public void orderAttemptOutcome(String strategy, OrderAttemptOutcome outcome) {
        Counter.builder("flashflow.order.attempt")
                .tag("strategy", strategy)
                .tag("outcome", outcome.name())
                .register(registry)
                .increment();
    }

    public void paymentOutcome(String code) {
        Counter.builder("flashflow.payment.outcome").tag("code", code).register(registry).increment();
    }

    public void expirationOutcome(String code) {
        Counter.builder("flashflow.expiration.outcome").tag("code", code).register(registry).increment();
    }

    public void admissionDecision(String decision) {
        Counter.builder("flashflow.admission.decision").tag("decision", decision).register(registry).increment();
    }

    public void admissionMySql(String outcome) {
        Counter.builder("flashflow.admission.mysql").tag("outcome", outcome).register(registry).increment();
    }

    public void admissionLifecycle(String operation, String outcome) {
        Counter.builder("flashflow.admission.lifecycle")
                .tag("operation", operation).tag("outcome", outcome).register(registry).increment();
    }

    public void reconciliation(String kind, String outcome) {
        Counter.builder("flashflow.admission.reconciliation")
                .tag("kind", kind).tag("outcome", outcome).register(registry).increment();
    }

    public void command(String operation, String outcome) {
        Counter.builder("flashflow.command")
                .tag("operation", operation).tag("outcome", outcome).register(registry).increment();
    }

    public void publication(String outcome) {
        Counter.builder("flashflow.messaging.publication")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void delivery(String outcome) {
        Counter.builder("flashflow.messaging.delivery")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void consumer(String operation, String outcome) {
        Counter.builder("flashflow.messaging.consumer")
                .tag("operation", operation).tag("outcome", outcome).register(registry).increment();
    }

    public void expirationTrigger(String outcome) {
        Counter.builder("flashflow.messaging.expiration.trigger")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void unresolvedWork(String kind, long count) {
        registry.gauge("flashflow.messaging.unresolved", java.util.List.of(
                io.micrometer.core.instrument.Tag.of("kind", kind)), count);
    }

    public void outbox(String operation, String outcome) {
        Counter.builder("flashflow.messaging.outbox")
                .tag("operation", operation).tag("outcome", outcome).register(registry).increment();
    }

    public void outboxSnapshot(long backlog, long oldestAgeMillis, long unresolved) {
        outboxBacklog.set(Math.max(0, backlog));
        outboxOldestAgeMillis.set(Math.max(0, oldestAgeMillis));
        outboxUnresolved.set(Math.max(0, unresolved));
    }
}
