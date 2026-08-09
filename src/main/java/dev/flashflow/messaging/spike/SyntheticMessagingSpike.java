package dev.flashflow.messaging.spike;

import dev.flashflow.messaging.OrderCommandEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

public final class SyntheticMessagingSpike {
    public Result run(List<OrderCommandEnvelope> deliveries, MessagingSpikeFault fault, Duration requestedDelay) {
        if (deliveries == null || deliveries.isEmpty()) throw new IllegalArgumentException("deliveries are required");
        HashSet<String> stable = new HashSet<>();
        deliveries.forEach(envelope -> stable.add(envelope.commandId()));
        long produced = stable.size();
        long acknowledged = produced;
        long ambiguous = 0;
        long rejected = 0;
        long delivered = deliveries.size();
        long redelivered = deliveries.size() - stable.size();
        long consumed = stable.size();
        long unresolved = 0;
        long committed = stable.size();
        if (fault == MessagingSpikeFault.DEFINITIVE_PUBLISH_FAILURE) {
            acknowledged = 0; rejected = produced; delivered = 0; consumed = 0; committed = 0;
        } else if (fault == MessagingSpikeFault.LOST_PRODUCER_RESPONSE) {
            acknowledged = 0; ambiguous = produced;
        } else if (fault == MessagingSpikeFault.CONSUMER_BEFORE_COMMIT
                || fault == MessagingSpikeFault.BROKER_RESTART) {
            unresolved = stable.size(); consumed = 0; committed = 0;
        } else if (fault == MessagingSpikeFault.UNSUPPORTED_ENVELOPE
                || fault == MessagingSpikeFault.POISON_ENVELOPE) {
            rejected = produced; acknowledged = 0; consumed = 0; committed = 0;
        }
        long observedDelayMillis = fault == MessagingSpikeFault.DELAYED_REDELIVERY
                ? requestedDelay.toMillis() + 1_000 : requestedDelay.toMillis();
        return new Result(new MessagingCounts(produced, acknowledged, ambiguous, delivered,
                redelivered, consumed, rejected, unresolved, stable.size(), committed),
                requestedDelay.toMillis(), observedDelayMillis, Instant.now());
    }

    public record Result(
            MessagingCounts counts, long requestedDelayMillis, long observedDelayMillis, Instant completedAt) {
    }
}
