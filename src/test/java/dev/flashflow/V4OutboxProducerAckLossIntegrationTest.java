package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.MessagingFaultInjector;
import dev.flashflow.messaging.persistence.OutboxMapper;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.support.RedisIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class V4OutboxProducerAckLossIntegrationTest extends RedisIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private OutboxMapper outbox;
    @Autowired private AdmissionAdministrationPort admission;
    @Autowired private MessagingFaultInjector faults;

    @DynamicPropertySource
    static void outboxMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "OUTBOX");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-v4-ack-loss-orders-" + RUN);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-v4-ack-loss-exp-" + RUN);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.injected-fault",
                () -> "AFTER_BROKER_ACK_BEFORE_OUTBOX_ACK_ONCE");
        registry.add("flashflow.messaging.outbox.poll-interval", () -> "100ms");
        registry.add("flashflow.messaging.outbox.initial-backoff", () -> "100ms");
        registry.add("flashflow.messaging.outbox.max-backoff", () -> "1s");
        registry.add("flashflow.messaging.outbox.lease-duration", () -> "4s");
        registry.add("flashflow.messaging.outbox.lease-owner", () -> "v4-ack-loss-" + RUN);
        registry.add("flashflow.expiration.order-ttl", () -> "PT30S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void brokerAcceptanceWithoutOutboxAckRepublishesOneIdentityAndCommitsOneEffect() throws Exception {
        String suffix = RUN.substring(0, 8);
        String sku = "sku-v4-ack-loss-" + suffix;
        String user = "user-v4-ack-loss-" + suffix;
        fixture().activeSku("activity-v4-ack-loss-" + suffix, sku, 1);
        String generation = "g-" + RUN;
        assertThat(admission.beginGeneration(sku, generation, 1, "fence-" + suffix)).isTrue();
        assertThat(admission.publishGeneration(sku, generation, "fence-" + suffix)).isTrue();

        AsyncOrderResponse accepted = post("key-v4-ack-loss-" + suffix, user, sku);
        await(Duration.ofSeconds(30), () -> {
            var row = outbox.findByCommandId(accepted.commandId());
            return row != null && "ACKNOWLEDGED".equals(row.status()) && row.attemptCount() >= 2
                    && ledger.summary(accepted.commandId()).status() == CommandStatus.COMPLETED
                    && faults.recoveredAfterFault();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class)).isEqualTo(1);
        assertThat(admission.snapshot(sku).confirmed()).isEqualTo(1);
    }

    private AsyncOrderResponse post(String key, String user, String sku) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        var response = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", user, "activitySkuId", sku), headers),
                AsyncOrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private static void await(Duration timeout, CheckedBoolean condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("condition did not pass within " + timeout);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
