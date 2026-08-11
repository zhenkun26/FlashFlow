package dev.flashflow.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MessagingConfigurationGuardTest {
    @Test
    void spikeModeRequiresIsolatedProfile() {
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(
                properties(MessagingProperties.Mode.SPIKE), new MockEnvironment());
        assertThatThrownBy(guard::validate).hasMessageContaining("messaging-spike");
    }

    @Test
    void spikeProfileRequiresExplicitMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("messaging-spike");
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(
                properties(MessagingProperties.Mode.DISABLED), environment);
        assertThatThrownBy(guard::validate).hasMessageContaining("requires Messaging SPIKE mode");
    }

    @Test
    void directModeRejectsSharedTopicIdentity() {
        MessagingProperties invalid = new MessagingProperties(MessagingProperties.Mode.DIRECT,
                "127.0.0.1:9876", "5.3.3", "5.3.4", "producer", "same", "orders",
                "same", "expiration", "dlq", java.time.Duration.ofSeconds(3), 1, 3, 14,
                java.time.Duration.ofSeconds(30), MessagingProperties.ConsumeStart.FIRST,
                MessagingProperties.Acknowledgement.SYNC_FLUSH, outbox());
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(invalid, new MockEnvironment());
        assertThatThrownBy(guard::validate).hasMessageContaining("topics must be distinct");
    }

    @Test
    void completeDirectAndOutboxConfigurationsPass() {
        new MessagingConfigurationGuard(properties(MessagingProperties.Mode.DIRECT), new MockEnvironment()).validate();
        new MessagingConfigurationGuard(properties(MessagingProperties.Mode.OUTBOX), new MockEnvironment()).validate();
    }

    @Test
    void outboxModeRejectsUnsafeLeaseAndEnabledCleanup() {
        MessagingProperties base = properties(MessagingProperties.Mode.OUTBOX);
        MessagingProperties shortLease = copy(base, new MessagingProperties.Outbox(50,
                java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(3), 8,
                java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(30), "test",
                true, false, java.time.Duration.ofDays(7)));
        assertThatThrownBy(() -> new MessagingConfigurationGuard(shortLease, new MockEnvironment()).validate())
                .hasMessageContaining("lease-duration");

        MessagingProperties cleanup = copy(base, new MessagingProperties.Outbox(50,
                java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(10), 8,
                java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(30), "test",
                true, true, java.time.Duration.ofDays(7)));
        assertThatThrownBy(() -> new MessagingConfigurationGuard(cleanup, new MockEnvironment()).validate())
                .hasMessageContaining("cleanup-disabled");
    }

    private static MessagingProperties properties(MessagingProperties.Mode mode) {
        return new MessagingProperties(mode, "127.0.0.1:9876", "5.3.3", "5.3.4", "producer",
                "orders", "orders-group", "expiration", "expiration-group", "dead-letter",
                java.time.Duration.ofSeconds(3), 1, 3, 14, java.time.Duration.ofSeconds(30),
                MessagingProperties.ConsumeStart.FIRST,
                MessagingProperties.Acknowledgement.SYNC_FLUSH, outbox());
    }

    private static MessagingProperties copy(MessagingProperties source, MessagingProperties.Outbox outbox) {
        return new MessagingProperties(source.mode(), source.namesrvAddr(), source.clientVersion(),
                source.brokerVersion(), source.producerGroup(), source.orderTopic(),
                source.orderConsumerGroup(), source.expirationTopic(), source.expirationConsumerGroup(),
                source.deadLetterTopic(), source.sendTimeout(), source.producerRetries(),
                source.maxReconsumeTimes(), source.delayLevel(), source.drainTimeout(), source.consumeFrom(),
                source.acknowledgement(), outbox);
    }

    private static MessagingProperties.Outbox outbox() {
        return new MessagingProperties.Outbox(50, java.time.Duration.ofMillis(250),
                java.time.Duration.ofSeconds(10), 8, java.time.Duration.ofMillis(500),
                java.time.Duration.ofSeconds(30), "test", true, false, java.time.Duration.ofDays(7));
    }
}
