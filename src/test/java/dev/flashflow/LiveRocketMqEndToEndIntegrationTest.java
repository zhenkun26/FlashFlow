package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandSummary;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.shared.web.ApiError;
import dev.flashflow.support.RedisIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.testcontainers.DockerClientFactory;
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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class LiveRocketMqEndToEndIntegrationTest extends RedisIntegrationTest {
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;

    @DynamicPropertySource
    static void liveMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "LIVE");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-e2e-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-e2e-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.delay-level", () -> 1);
        registry.add("flashflow.messaging.max-reconsume-times", () -> 3);
        registry.add("flashflow.expiration.order-ttl", () -> "PT1S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void httpBoundaryThroughLiveBrokerCommitsOnceAndDelayedTriggerClosesOnce() throws Exception {
        fixture().activeSku("activity-live", "sku-live", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-live", generation, 1, "fence-live")).isTrue();
        assertThat(admission.publishGeneration("sku-live", generation, "fence-live")).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-live");
        var response = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "user-live", "activitySkuId", "sku-live"), headers),
                AsyncOrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String commandId = response.getBody().commandId();

        await(Duration.ofSeconds(20), () -> ledger.summary(commandId).status() == CommandStatus.COMPLETED);
        HttpHeaders statusHeaders = new HttpHeaders();
        statusHeaders.set("X-User-Id", "user-live");
        var status = http.exchange(response.getHeaders().getLocation(), HttpMethod.GET,
                new HttpEntity<>(statusHeaders), CommandSummary.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody().status()).isEqualTo(CommandStatus.COMPLETED);
        HttpHeaders otherCaller = new HttpHeaders();
        otherCaller.set("X-User-Id", "another-user");
        assertThat(http.exchange(response.getHeaders().getLocation(), HttpMethod.GET,
                new HttpEntity<>(otherCaller), CommandSummary.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        String orderId = ledger.summary(commandId).orderId();
        assertThat(orderId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, orderId))
                .isEqualTo(1);

        await(Duration.ofSeconds(20), () -> "CLOSED_UNPAID".equals(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_movement WHERE operation_id = ?",
                Integer.class, "release:" + orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_stock FROM activity_sku_stock WHERE id = 'sku-live'", Integer.class))
                .isEqualTo(1);
        var finalAdmission = admission.snapshot("sku-live");
        assertThat(finalAdmission.remainingCapacity()).isEqualTo(1);
        assertThat(finalAdmission.held()).isZero();
        assertThat(finalAdmission.confirmed()).isZero();
        assertThat(finalAdmission.quarantined()).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void synchronousV1KeepsCreatedAndReplaySemanticsWhileLiveMessagingIsEnabled() {
        fixture().activeSku("activity-v1-live", "sku-v1-live", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-v1-live", generation, 1, "fence-v1-live")).isTrue();
        assertThat(admission.publishGeneration("sku-v1-live", generation, "fence-v1-live")).isTrue();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-v1-live");
        HttpEntity<java.util.Map<String, String>> request = new HttpEntity<>(
                java.util.Map.of("userId", "user-v1-live", "activitySkuId", "sku-v1-live"), headers);

        var first = http.exchange("/api/v1/orders", HttpMethod.POST, request, OrderResult.class);
        var replay = http.exchange("/api/v1/orders", HttpMethod.POST, request, OrderResult.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().orderId()).isEqualTo(first.getBody().orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = 'user-v1-live'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void asynchronousHttpRejectsConflictingPayloadInvalidInputAndUnknownIdentity() throws Exception {
        fixture().activeSku("activity-contract-a", "sku-contract-a", 1);
        fixture().activeSku("activity-contract-b", "sku-contract-b", 1);
        for (String sku : java.util.List.of("sku-contract-a", "sku-contract-b")) {
            String generation = "g-" + UUID.randomUUID();
            assertThat(admission.beginGeneration(sku, generation, 1, "fence-" + sku)).isTrue();
            assertThat(admission.publishGeneration(sku, generation, "fence-" + sku)).isTrue();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "key-contract");
        var accepted = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "user-contract",
                        "activitySkuId", "sku-contract-a"), headers), AsyncOrderResponse.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await(Duration.ofSeconds(20),
                () -> ledger.summary(accepted.getBody().commandId()).status() == CommandStatus.COMPLETED);

        var conflict = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "user-contract",
                        "activitySkuId", "sku-contract-b"), headers), ApiError.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().code()).isEqualTo("COMMAND_CONFLICT");

        var invalid = http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", "", "activitySkuId", "sku-contract-a"), headers),
                ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders statusHeaders = new HttpHeaders();
        statusHeaders.set("X-User-Id", "user-contract");
        assertThat(http.exchange("/api/v2/order-commands/" + "f".repeat(64), HttpMethod.GET,
                new HttpEntity<>(statusHeaders), CommandSummary.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void poisonEnvelopeIsPublishedToTheRealDeadLetterTopicWithBoundedMetadata() throws Exception {
        String commandId = "d".repeat(64);
        AtomicReference<MessageExt> captured = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        DefaultMQPushConsumer dlqConsumer = new DefaultMQPushConsumer("flashflow-live-dlq-test-" + UUID.randomUUID());
        dlqConsumer.setNamesrvAddr("127.0.0.1:9876");
        dlqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        dlqConsumer.subscribe("flashflow-order-dead-letter-v1", "ORDER_DLQ");
        dlqConsumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                (messages, context) -> {
                    captured.compareAndSet(null, messages.getFirst());
                    received.countDown();
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
        DefaultMQProducer producer = new DefaultMQProducer("flashflow-live-poison-test-" + UUID.randomUUID());
        producer.setNamesrvAddr("127.0.0.1:9876");
        try {
            dlqConsumer.start();
            producer.start();
            Message poison = new Message("flashflow-order-command-v1", "ORDER_V1", commandId,
                    new byte[]{1, 2, 3});
            producer.send(poison);
            assertThat(received.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getKeys()).isEqualTo(commandId);
            assertThat(captured.get().getUserProperty("sourceTopic")).isEqualTo("flashflow-order-command-v1");
            assertThat(captured.get().getUserProperty("reason")).isEqualTo("INVALID_ENVELOPE");
            assertThat(captured.get().getUserProperty("attempts")).isEqualTo("1");
        } finally {
            producer.shutdown();
            dlqConsumer.shutdown();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void brokerOutageDoesNotClaimAcceptanceAndSameIdentityRecoversAfterRestart() throws Exception {
        fixture().activeSku("activity-restart", "sku-restart", 1);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration("sku-restart", generation, 1, "fence-restart")).isTrue();
        assertThat(admission.publishGeneration("sku-restart", generation, "fence-restart")).isTrue();

        var docker = DockerClientFactory.instance().client();
        var matches = docker.listContainersCmd().withShowAll(true)
                .withNameFilter(java.util.List.of("flashflow-rocketmq-broker-1")).exec();
        assertThat(matches).hasSize(1);
        String brokerId = matches.getFirst().getId();
        docker.stopContainerCmd(brokerId).withTimeout(10).exec();
        await(Duration.ofSeconds(10), () -> !Boolean.TRUE.equals(
                docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));
        try {
            var unavailable = post("key-restart", "user-restart", "sku-restart");
            assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(unavailable.getBody().status())
                    .isIn(CommandStatus.RETRYABLE, CommandStatus.UNRESOLVED);
        } finally {
            docker.startContainerCmd(brokerId).exec();
            await(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(
                    docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));
        }

        AtomicReference<org.springframework.http.ResponseEntity<AsyncOrderResponse>> accepted = new AtomicReference<>();
        await(Duration.ofSeconds(30), () -> {
            var candidate = post("key-restart", "user-restart", "sku-restart");
            if (candidate.getStatusCode() == HttpStatus.ACCEPTED) {
                accepted.set(candidate);
                return true;
            }
            return false;
        });
        String commandId = accepted.get().getBody().commandId();
        await(Duration.ofSeconds(20), () -> ledger.summary(commandId).status() == CommandStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = 'user-restart'",
                Integer.class)).isEqualTo(1);
    }

    private org.springframework.http.ResponseEntity<AsyncOrderResponse> post(
            String key, String user, String sku) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.exchange("/api/v2/orders", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("userId", user, "activitySkuId", sku), headers),
                AsyncOrderResponse.class);
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
