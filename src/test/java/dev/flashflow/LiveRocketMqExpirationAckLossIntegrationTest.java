package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.MessagingFaultInjector;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.support.RedisIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
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
class LiveRocketMqExpirationAckLossIntegrationTest extends RedisIntegrationTest {
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;
    @Autowired private MeterRegistry meters;
    @Autowired private MessagingFaultInjector faults;

    @DynamicPropertySource
    static void liveMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "DIRECT");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-exp-ack-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-exp-ack-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.delay-level", () -> 1);
        registry.add("flashflow.messaging.injected-fault",
                () -> "AFTER_EXPIRATION_RESULT_BEFORE_ACK_ONCE");
        registry.add("flashflow.expiration.order-ttl", () -> "PT0S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void lostExpirationAcknowledgementRedeliversWithoutDuplicateClosure() throws Exception {
        fixture().activeSku("activity-exp-ack", "sku-exp-ack", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-exp-ack", generation, 1, "fence-exp-ack")).isTrue();
        assertThat(admission.publishGeneration("sku-exp-ack", generation, "fence-exp-ack")).isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-exp-ack");
        var response = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "user-exp-ack", "activitySkuId", "sku-exp-ack"),
                        headers), AsyncOrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await(Duration.ofSeconds(20),
                () -> ledger.summary(response.getBody().commandId()).status() == CommandStatus.COMPLETED);
        String orderId = ledger.summary(response.getBody().commandId()).orderId();

        await(Duration.ofSeconds(30), () -> "CLOSED_UNPAID".equals(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                && metric("CONSUME_RETRY") >= 1 && faults.recoveredAfterFault());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE operation_id = ?",
                Integer.class, "release:" + orderId)).isEqualTo(1);
        assertThat(admission.snapshot("sku-exp-ack").remainingCapacity()).isEqualTo(1);
    }

    private double metric(String outcome) {
        var counter = meters.find("flashflow.messaging.expiration.trigger").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
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
