package dev.flashflow.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "flashflow.messaging")
public record MessagingProperties(
        @NotNull Mode mode,
        @NotBlank @Size(max = 256) String namesrvAddr,
        @NotBlank @Size(max = 64) String clientVersion,
        @NotBlank @Size(max = 64) String brokerVersion,
        @NotBlank @Size(max = 128) String producerGroup,
        @NotBlank @Size(max = 128) String orderTopic,
        @NotBlank @Size(max = 128) String orderConsumerGroup,
        @NotBlank @Size(max = 128) String expirationTopic,
        @NotBlank @Size(max = 128) String expirationConsumerGroup,
        @NotBlank @Size(max = 128) String deadLetterTopic,
        @NotNull Duration sendTimeout,
        @Min(0) @Max(100) int producerRetries,
        @Min(0) @Max(100) int maxReconsumeTimes,
        @Min(1) @Max(18) int delayLevel,
        @NotNull Duration drainTimeout,
        @NotNull ConsumeStart consumeFrom,
        @Valid @NotNull Acknowledgement acknowledgement) {
    public enum Mode {
        DISABLED,
        SPIKE,
        LIVE
    }

    public enum Acknowledgement {
        SYNC_FLUSH
    }

    public enum ConsumeStart {
        FIRST,
        LAST
    }
}
