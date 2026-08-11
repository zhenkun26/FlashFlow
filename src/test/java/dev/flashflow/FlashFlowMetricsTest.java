package dev.flashflow.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.OrderAttemptOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class FlashFlowMetricsTest {
    @Test
    void recordsOnlyBoundedStrategyAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlashFlowMetrics metrics = new FlashFlowMetrics(registry);

        metrics.orderAttempt("OPTIMISTIC");
        metrics.orderAttemptOutcome("OPTIMISTIC", OrderAttemptOutcome.OPTIMISTIC_CONFLICT);
        metrics.orderAttemptOutcome("OPTIMISTIC", OrderAttemptOutcome.RETRY_EXHAUSTED);
        metrics.orderOutcome("RETRYABLE_CONTENTION");
        metrics.orderOutcome("CREATED");
        metrics.orderOutcome("SOLD_OUT");
        metrics.orderOutcome("UNEXPECTED_FAILURE");

        assertThat(registry.get("flashflow.order.attempt")
                .tags("strategy", "OPTIMISTIC", "outcome", "STARTED").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.order.attempt")
                .tags("strategy", "OPTIMISTIC", "outcome", "OPTIMISTIC_CONFLICT").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.order.attempt")
                .tags("strategy", "OPTIMISTIC", "outcome", "RETRY_EXHAUSTED").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.order.outcome").tag("code", "CREATED").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.order.outcome").tag("code", "SOLD_OUT").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.order.outcome").tag("code", "UNEXPECTED_FAILURE").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("strategy", "outcome", "code")));
    }

    @Test
    void recognizesDirectAndNestedConnectionAcquisitionFailures() {
        CannotGetJdbcConnectionException failure = new CannotGetJdbcConnectionException(
                "pool exhausted", new SQLTransientConnectionException("timeout"));

        assertThat(OrderApplicationService.isConnectionAcquisitionFailure(failure)).isTrue();
        assertThat(OrderApplicationService.isConnectionAcquisitionFailure(new IllegalStateException("other")))
                .isFalse();
    }

    @Test
    void recordsBoundedOutboxEventsAndBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlashFlowMetrics metrics = new FlashFlowMetrics(registry);

        metrics.outbox("CLAIM", "TAKEOVER");
        metrics.outbox("DISPOSITION", "EXHAUSTED");
        metrics.outboxSnapshot(7, 1250, 2);

        assertThat(registry.get("flashflow.messaging.outbox")
                .tags("operation", "CLAIM", "outcome", "TAKEOVER").counter().count()).isEqualTo(1);
        assertThat(registry.get("flashflow.messaging.outbox.backlog").gauge().value()).isEqualTo(7);
        assertThat(registry.get("flashflow.messaging.outbox.oldest.age.millis").gauge().value())
                .isEqualTo(1250);
        assertThat(registry.get("flashflow.messaging.outbox.unresolved").gauge().value()).isEqualTo(2);
    }
}
