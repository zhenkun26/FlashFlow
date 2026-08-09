package dev.flashflow.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MessagingConfigurationGuardTest {
    @Test
    void spikeModeRequiresIsolatedProfile() {
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(
                new MessagingProperties(MessagingProperties.Mode.SPIKE), new MockEnvironment());
        assertThatThrownBy(guard::validate).hasMessageContaining("messaging-spike");
    }

    @Test
    void spikeProfileRequiresExplicitMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("messaging-spike");
        MessagingConfigurationGuard guard = new MessagingConfigurationGuard(
                new MessagingProperties(MessagingProperties.Mode.DISABLED), environment);
        assertThatThrownBy(guard::validate).hasMessageContaining("requires Messaging SPIKE mode");
    }
}
