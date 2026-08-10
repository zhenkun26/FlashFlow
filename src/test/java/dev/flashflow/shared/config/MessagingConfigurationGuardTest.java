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
    void liveModeRejectsSharedTopicIdentity() {
        MessagingProperties invalid = new MessagingProperties(MessagingProperties.Mode.LIVE,
                "127.0.0.1:9876", "5.3.3", "5.3.4", "producer", "same", "orders",
                "same", "expiration", "dlq", java.time.Duration.ofSeconds(3), 1, 3, 14,
                java.time.Duration.ofSeconds(30), MessagingProperties.ConsumeStart.FIRST,
                MessagingProperties.Acknowledgement.SYNC_FLUSH);
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(invalid, new MockEnvironment());
        assertThatThrownBy(guard::validate).hasMessageContaining("topics must be distinct");
    }

    @Test
    void completeLiveConfigurationPasses() {
        new MessagingConfigurationGuard(properties(MessagingProperties.Mode.LIVE), new MockEnvironment()).validate();
    }

    private static MessagingProperties properties(MessagingProperties.Mode mode) {
        return new MessagingProperties(mode, "127.0.0.1:9876", "5.3.3", "5.3.4", "producer",
                "orders", "orders-group", "expiration", "expiration-group", "dead-letter",
                java.time.Duration.ofSeconds(3), 1, 3, 14, java.time.Duration.ofSeconds(30),
                MessagingProperties.ConsumeStart.FIRST,
                MessagingProperties.Acknowledgement.SYNC_FLUSH);
    }
}
