package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.support.RedisIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class V3ControlledComparisonIntegrationTest extends RedisIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(V3ControlledComparisonIntegrationTest.class);
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void liveMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "DIRECT");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.producer-group", () -> "flashflow-comparison-producer-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-comparison-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-comparison-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.expiration.order-ttl", () -> "PT30S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void separatesSynchronousCompletionFromV3AcceptanceAndCompletion() throws Exception {
        String suffix = GROUP_SUFFIX.substring(0, 8);
        String syncSku = "sku-control-sync-" + suffix;
        String asyncSku = "sku-control-v3-" + suffix;
        String syncUser = "user-control-sync-" + suffix;
        String asyncUser = "user-control-v3-" + suffix;
        ready("activity-control-sync-" + suffix, syncSku);
        ready("activity-control-v3-" + suffix, asyncSku);
        double publicationsBefore = metric(
                "flashflow.messaging.publication", "outcome", "BROKER_ACKNOWLEDGED");
        double deliveriesBefore = metric("flashflow.messaging.delivery", "outcome", "RECEIVED");

        long syncStarted = System.nanoTime();
        var sync = http.exchange("/api/v1/orders", HttpMethod.POST,
                request("key-control-sync-" + suffix, syncUser, syncSku), OrderResult.class);
        long syncCompleted = System.nanoTime();
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sync.getBody().code()).isEqualTo(OrderResultCode.CREATED);

        long asyncStarted = System.nanoTime();
        var accepted = postUntilAccepted(Duration.ofSeconds(60),
                request("key-control-v3-" + suffix, asyncUser, asyncSku));
        long asyncAccepted = System.nanoTime();
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().cause()).isNotEqualTo("DURABLE_REPLAY");
        String commandId = accepted.getBody().commandId();
        await(Duration.ofSeconds(20), () -> ledger.summary(commandId).status() == CommandStatus.COMPLETED);
        long asyncCompleted = System.nanoTime();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id IN (?, ?)", Integer.class,
                syncUser, asyncUser)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(available_stock) FROM activity_sku_stock WHERE id IN (?, ?)",
                Integer.class, syncSku, asyncSku)).isZero();
        assertThat(metric("flashflow.messaging.publication", "outcome", "BROKER_ACKNOWLEDGED")
                - publicationsBefore)
                .isGreaterThanOrEqualTo(1);
        assertThat(metric("flashflow.messaging.delivery", "outcome", "RECEIVED") - deliveriesBefore)
                .isGreaterThanOrEqualTo(1);

        log.info("V3_CONTROLLED_COMPARISON sync_acceptance_ms={} sync_completion_ms={} "
                        + "v3_acceptance_ms={} v3_completion_ms={} business_created=2 "
                        + "technical_outcomes=SYNC_CREATED,V3_ACCEPTED_V3_COMPLETED message_observations=ACK_AND_DELIVERY "
                        + "scope=LOCAL_SINGLE_REQUEST_NOT_CAPACITY",
                millis(syncCompleted - syncStarted), millis(syncCompleted - syncStarted),
                millis(asyncAccepted - asyncStarted), millis(asyncCompleted - asyncStarted));
    }

    private void ready(String activityId, String skuId) {
        fixture().activeSku(activityId, skuId, 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration(skuId, generation, 1, "fence-" + skuId)).isTrue();
        assertThat(admission.publishGeneration(skuId, generation, "fence-" + skuId)).isTrue();
    }

    private static HttpEntity<java.util.Map<String, String>> request(String key, String user, String sku) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(java.util.Map.of("userId", user, "activitySkuId", sku), headers);
    }

    private ResponseEntity<AsyncOrderResponse> postUntilAccepted(
            Duration timeout, HttpEntity<java.util.Map<String, String>> request) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ResponseEntity<AsyncOrderResponse> response;
        do {
            response = http.exchange("/api/v2/orders", HttpMethod.POST, request, AsyncOrderResponse.class);
            if (response.getStatusCode() == HttpStatus.ACCEPTED) return response;
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            Thread.sleep(250);
        } while (Instant.now().isBefore(deadline));
        AsyncOrderResponse body = response.getBody();
        throw new AssertionError("direct publication did not recover within " + timeout
                + " status=" + (body == null ? "NO_BODY" : body.status())
                + " cause=" + (body == null ? "NO_BODY" : body.cause()));
    }

    private double metric(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }

    private static void await(Duration timeout, CheckedBoolean condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("condition did not pass within " + timeout);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
