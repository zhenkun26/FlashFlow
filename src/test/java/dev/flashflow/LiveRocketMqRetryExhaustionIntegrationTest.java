package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.support.RedisIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
class LiveRocketMqRetryExhaustionIntegrationTest extends RedisIntegrationTest {
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;

    @DynamicPropertySource
    static void liveMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "LIVE");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-exhaustion-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-exhaustion-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.max-reconsume-times", () -> 1);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.injected-fault", () -> "BEFORE_CONSUME_ALWAYS");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void retryExhaustionDeadLettersWithoutBusinessEffectOrAdmissionRelease() throws Exception {
        fixture().activeSku("activity-exhaustion", "sku-exhaustion", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-exhaustion", generation, 1, "fence-exhaustion")).isTrue();
        assertThat(admission.publishGeneration("sku-exhaustion", generation, "fence-exhaustion")).isTrue();
        AsyncOrderResponse first = post("key-exhaustion", "user-exhaustion", "sku-exhaustion");

        await(Duration.ofSeconds(40), () -> jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM order_command_ledger WHERE command_id = ?",
                Boolean.class, first.commandId()));
        assertThat(ledger.summary(first.commandId()).status()).isEqualTo(CommandStatus.RETRYABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = 'user-exhaustion'",
                Integer.class)).isZero();
        var held = admission.snapshot("sku-exhaustion");
        assertThat(held.held()).isEqualTo(1);
        assertThat(held.remainingCapacity()).isZero();

        AsyncOrderResponse replay = post("key-exhaustion", "user-exhaustion", "sku-exhaustion");
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_ledger WHERE command_id = ?",
                Integer.class, first.commandId())).isEqualTo(1);
        assertThat(admission.snapshot("sku-exhaustion").held()).isEqualTo(1);
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
            Thread.sleep(200);
        }
        throw new AssertionError("condition did not pass within " + timeout);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
