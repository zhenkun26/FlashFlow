package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.messaging.spike.MessagingSpikeFault;
import dev.flashflow.messaging.spike.SyntheticMessagingSpike;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticMessagingSpikeTest {
    private final SyntheticMessagingSpike spike = new SyntheticMessagingSpike();

    @Test
    void duplicateDeliveryAndLostResponseRemainReconciled() {
        OrderCommandEnvelope envelope = envelope();
        var duplicate = spike.run(List.of(envelope, envelope), MessagingSpikeFault.DUPLICATE_DELIVERY,
                Duration.ofSeconds(1));
        assertThat(duplicate.counts().redelivered()).isEqualTo(1);
        assertThat(duplicate.counts().committedEffects()).isEqualTo(1);
        assertThat(duplicate.counts().reconciles()).isTrue();

        var ambiguous = spike.run(List.of(envelope), MessagingSpikeFault.LOST_PRODUCER_RESPONSE,
                Duration.ZERO);
        assertThat(ambiguous.counts().ambiguous()).isEqualTo(1);
        assertThat(ambiguous.counts().reconciles()).isTrue();
    }

    @Test
    void failuresAndDelayAreBoundedEvidence() {
        for (MessagingSpikeFault fault : List.of(MessagingSpikeFault.DEFINITIVE_PUBLISH_FAILURE,
                MessagingSpikeFault.CONSUMER_BEFORE_COMMIT, MessagingSpikeFault.CONSUMER_AFTER_COMMIT_BEFORE_ACK,
                MessagingSpikeFault.ACKNOWLEDGEMENT_LOSS, MessagingSpikeFault.BROKER_RESTART,
                MessagingSpikeFault.UNSUPPORTED_ENVELOPE, MessagingSpikeFault.POISON_ENVELOPE)) {
            assertThat(spike.run(List.of(envelope()), fault, Duration.ZERO).counts().reconciles()).isTrue();
        }
        var delayed = spike.run(List.of(envelope()), MessagingSpikeFault.DELAYED_REDELIVERY,
                Duration.ofSeconds(2));
        assertThat(delayed.observedDelayMillis()).isGreaterThanOrEqualTo(delayed.requestedDelayMillis());
    }

    private static OrderCommandEnvelope envelope() {
        return new OrderCommandEnvelope(1, "a".repeat(64), "user", "sku", "key",
                "b".repeat(64), Instant.EPOCH, "trace");
    }
}
