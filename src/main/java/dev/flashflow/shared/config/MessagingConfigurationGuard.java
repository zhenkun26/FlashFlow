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
        if (activeMode() && spikeProfile) {
            throw new IllegalStateException("Active messaging mode cannot use the isolated messaging-spike profile");
        }
        if (activeMode()) {
            require(properties.sendTimeout().isPositive(), "send-timeout must be positive");
            require(properties.drainTimeout().isPositive(), "drain-timeout must be positive");
            require(!properties.orderTopic().equals(properties.expirationTopic()),
                    "order and expiration topics must be distinct");
            require(!properties.deadLetterTopic().equals(properties.orderTopic())
                            && !properties.deadLetterTopic().equals(properties.expirationTopic()),
                    "dead-letter topic must be distinct");
            require(!properties.orderConsumerGroup().equals(properties.expirationConsumerGroup()),
                    "order and expiration consumer groups must be distinct");
            require("5.3.3".equals(properties.clientVersion()), "RocketMQ client version must be pinned to 5.3.3");
            require("5.3.4".equals(properties.brokerVersion()), "RocketMQ broker version must be pinned to 5.3.4");
        }
        if (properties.mode() == MessagingProperties.Mode.OUTBOX) {
            MessagingProperties.Outbox outbox = properties.outbox();
            require(outbox.pollInterval().isPositive(), "outbox poll-interval must be positive");
            require(outbox.leaseDuration().compareTo(properties.sendTimeout().plusMillis(100)) > 0,
                    "outbox lease-duration must exceed send-timeout plus safety margin");
            require(outbox.initialBackoff().isPositive(), "outbox initial-backoff must be positive");
            require(outbox.maxBackoff().compareTo(outbox.initialBackoff()) >= 0,
                    "outbox max-backoff must not be shorter than initial-backoff");
            require(outbox.retention().isPositive(), "outbox retention must be positive");
            require(outbox.dispatchEnabled(), "OUTBOX mode requires dispatch-enabled");
            require(!outbox.cleanupEnabled(), "V4 verification requires cleanup-disabled retention");
        }
    }

    private boolean activeMode() {
        return properties.mode() == MessagingProperties.Mode.DIRECT
                || properties.mode() == MessagingProperties.Mode.OUTBOX;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
