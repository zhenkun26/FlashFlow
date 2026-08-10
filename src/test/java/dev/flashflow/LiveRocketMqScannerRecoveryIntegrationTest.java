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
class LiveRocketMqScannerRecoveryIntegrationTest extends RedisIntegrationTest {
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;

    @DynamicPropertySource
    static void liveMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "LIVE");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-scanner-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-scanner-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.delay-level", () -> 18);
        registry.add("flashflow.expiration.order-ttl", () -> "PT1S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> true);
        registry.add("flashflow.expiration.scan-delay", () -> "PT0.2S");
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void scannerClosesWhenTheBrokerDelayExceedsTheDeclaredRecoveryBound() throws Exception {
        fixture().activeSku("activity-scanner", "sku-scanner", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-scanner", generation, 1, "fence-scanner")).isTrue();
        assertThat(admission.publishGeneration("sku-scanner", generation, "fence-scanner")).isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-scanner");
        var response = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "user-scanner", "activitySkuId", "sku-scanner"),
                        headers),
                AsyncOrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String commandId = response.getBody().commandId();
        await(Duration.ofSeconds(20), () -> ledger.summary(commandId).status() == CommandStatus.COMPLETED);
        String orderId = ledger.summary(commandId).orderId();

        await(Duration.ofSeconds(10), () -> "CLOSED_UNPAID".equals(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE operation_id = ?",
                Integer.class, "release:" + orderId)).isEqualTo(1);
        assertThat(admission.snapshot("sku-scanner").remainingCapacity()).isEqualTo(1);
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
