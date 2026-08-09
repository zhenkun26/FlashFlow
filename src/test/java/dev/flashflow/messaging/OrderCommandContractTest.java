package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderCommandContractTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void versionOneEnvelopeRoundTrips() throws Exception {
        OrderCommandEnvelope value = envelope();
        assertThat(json.readValue(json.writeValueAsBytes(value), OrderCommandEnvelope.class)).isEqualTo(value);
    }

    @Test
    void rejectsUnsupportedVersionAndInvalidAcceptance() {
        assertThatThrownBy(() -> new OrderCommandEnvelope(2, "a".repeat(64), "user", "sku", "key",
                "b".repeat(64), Instant.EPOCH, "trace")).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> new FutureAsyncOrderContract.Accepted(
                202, "command", URI.create("/api/v2/order-commands/command"), "order"))
                .hasMessageContaining("cannot claim");
    }

    static OrderCommandEnvelope envelope() {
        return new OrderCommandEnvelope(1, "a".repeat(64), "user", "sku", "key",
                "b".repeat(64), Instant.parse("2026-08-09T00:00:00Z"), "trace");
    }
}
