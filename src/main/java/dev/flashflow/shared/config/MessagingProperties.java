package dev.flashflow.shared.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "flashflow.messaging")
public record MessagingProperties(@NotNull Mode mode) {
    public enum Mode {
        DISABLED,
        SPIKE
    }
}
