package dev.flashflow.shared.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class MessagingConfigurationGuard {
    private final MessagingProperties properties;
    private final Environment environment;

    public MessagingConfigurationGuard(MessagingProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean spikeProfile = Arrays.asList(environment.getActiveProfiles()).contains("messaging-spike");
        if (properties.mode() == MessagingProperties.Mode.SPIKE && !spikeProfile) {
            throw new IllegalStateException("Messaging SPIKE mode requires the messaging-spike profile");
        }
        if (properties.mode() == MessagingProperties.Mode.DISABLED && spikeProfile) {
            throw new IllegalStateException("messaging-spike profile requires Messaging SPIKE mode");
        }
    }
}
