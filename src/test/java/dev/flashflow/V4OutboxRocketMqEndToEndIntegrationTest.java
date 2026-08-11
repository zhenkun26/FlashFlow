package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
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
import org.testcontainers.DockerClientFactory;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class V4OutboxRocketMqEndToEndIntegrationTest extends RedisIntegrationTest {
    private static final String GROUP_SUFFIX = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private OutboxMapper outbox;
    @Autowired private AdmissionAdministrationPort admission;

    @DynamicPropertySource
    static void outboxMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "OUTBOX");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-v4-orders-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-v4-exp-" + GROUP_SUFFIX);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.outbox.poll-interval", () -> "100ms");
        registry.add("flashflow.messaging.outbox.initial-backoff", () -> "100ms");
        registry.add("flashflow.messaging.outbox.max-backoff", () -> "1s");
        registry.add("flashflow.messaging.outbox.max-attempts", () -> 64);
        registry.add("flashflow.messaging.outbox.lease-duration", () -> "10s");
        registry.add("flashflow.messaging.outbox.lease-owner", () -> "v4-e2e-" + GROUP_SUFFIX);
        registry.add("flashflow.expiration.order-ttl", () -> "PT30S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void durableAcceptanceDispatchesAndCommitsOneRecoverableResult() throws Exception {
        String suffix = GROUP_SUFFIX.substring(0, 8);
        String sku = "sku-v4-" + suffix;
        String user = "user-v4-" + suffix;
        prepareAdmission("activity-v4-" + suffix, sku, 1);
        AsyncOrderResponse response = post("key-v4-" + suffix, user, sku);
        assertThat(response.status()).isEqualTo(CommandStatus.ACCEPTED);

        await(Duration.ofSeconds(30), () -> ledger.summary(response.commandId()).status() == CommandStatus.COMPLETED);
        assertThat(outbox.findByCommandId(response.commandId()).status()).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void brokerOutageLeavesDurableBacklogAndRecoveryPublishesWithoutCallerRetry() throws Exception {
        String suffix = GROUP_SUFFIX.substring(0, 8);
        String sku = "sku-v4-outage-" + suffix;
        String user = "user-v4-outage-" + suffix;
        prepareAdmission("activity-v4-outage-" + suffix, sku, 1);
        var docker = DockerClientFactory.instance().client();
        var matches = docker.listContainersCmd().withShowAll(true)
                .withNameFilter(java.util.List.of("flashflow-rocketmq-broker-1")).exec();
        assertThat(matches).hasSize(1);
        String brokerId = matches.getFirst().getId();
        docker.stopContainerCmd(brokerId).withTimeout(10).exec();
        await(Duration.ofSeconds(10), () -> !Boolean.TRUE.equals(
                docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));
        AsyncOrderResponse accepted;
        try {
            accepted = post("key-v4-outage-" + suffix, user, sku);
            assertThat(accepted.status()).isEqualTo(CommandStatus.ACCEPTED);
            assertThat(outbox.findByCommandId(accepted.commandId()).status())
                    .isIn("READY", "CLAIMED", "RETRYABLE");
        } finally {
            docker.startContainerCmd(brokerId).exec();
            await(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(
                    docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));
        }

        String commandId = accepted.commandId();
        await(Duration.ofSeconds(30), () -> ledger.summary(commandId).status() == CommandStatus.COMPLETED
                && "ACKNOWLEDGED".equals(outbox.findByCommandId(commandId).status()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
    }

    private void prepareAdmission(String activity, String sku, int stock) {
        fixture().activeSku(activity, sku, stock);
        String generation = "g-" + UUID.randomUUID();
        assertThat(admission.beginGeneration(sku, generation, stock, "fence-" + sku)).isTrue();
        assertThat(admission.publishGeneration(sku, generation, "fence-" + sku)).isTrue();
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
