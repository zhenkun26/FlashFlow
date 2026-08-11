package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.MessagingFaultInjector;
import dev.flashflow.messaging.OrderCommandPublisher;
import dev.flashflow.messaging.outbox.OutboxDispatcher;
import dev.flashflow.messaging.outbox.OutboxStore;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import dev.flashflow.support.RedisIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class V4OutboxConcurrentRedeliveryIntegrationTest extends RedisIntegrationTest {
    private static final int COMMANDS = 8;
    private static final String RUN = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;
    @Autowired private OutboxStore outbox;
    @Autowired private OrderCommandPublisher publisher;
    @Autowired private MessagingProperties properties;
    @Autowired private FlashFlowMetrics metrics;
    @Autowired private MessagingFaultInjector faults;
    @Autowired private MeterRegistry meters;
    @Autowired private Clock clock;

    @DynamicPropertySource
    static void outboxMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "OUTBOX");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-v4-concurrent-orders-" + RUN);
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-v4-concurrent-exp-" + RUN);
        registry.add("flashflow.messaging.consume-from", () -> "LAST");
        registry.add("flashflow.messaging.injected-fault", () -> "AFTER_DURABLE_RESULT_BEFORE_ACK_ONCE");
        registry.add("flashflow.messaging.outbox.batch-size", () -> 2);
        registry.add("flashflow.messaging.outbox.poll-interval", () -> "250ms");
        registry.add("flashflow.messaging.outbox.initial-backoff", () -> "100ms");
        registry.add("flashflow.messaging.outbox.max-backoff", () -> "1s");
        registry.add("flashflow.messaging.outbox.lease-duration", () -> "10s");
        registry.add("flashflow.messaging.outbox.lease-owner", () -> "v4-concurrent-" + RUN);
        registry.add("flashflow.expiration.order-ttl", () -> "PT30S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void concurrentDispatchersAndConsumerRedeliveryPreserveAllInvariants() throws Exception {
        String suffix = RUN.substring(0, 8);
        String sku = "sku-v4-concurrent-" + suffix;
        fixture().activeSku("activity-v4-concurrent-" + suffix, sku, COMMANDS);
        String generation = "g-" + RUN;
        assertThat(admission.beginGeneration(sku, generation, COMMANDS, "fence-" + suffix)).isTrue();
        assertThat(admission.publishGeneration(sku, generation, "fence-" + suffix)).isTrue();

        List<String> commandIds = new ArrayList<>();
        for (int index = 0; index < COMMANDS; index++) {
            commandIds.add(post("key-" + suffix + "-" + index,
                    "user-" + suffix + "-" + index, sku).commandId());
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatchers = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> new OutboxDispatcher(outbox, publisher, properties, metrics, faults, clock))
                    .toList();
            var results = dispatchers.stream().map(dispatcher -> executor.submit(dispatcher::dispatchBatch)).toList();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }

        await(Duration.ofSeconds(60), () -> commandIds.stream().allMatch(commandId ->
                ledger.summary(commandId).status() == CommandStatus.COMPLETED)
                && faults.recoveredAfterFault());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_command_outbox WHERE status = 'ACKNOWLEDGED'",
                Integer.class)).isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT MIN(attempt_count) FROM order_command_outbox", Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MAX(attempt_count) FROM order_command_outbox", Integer.class))
                .isLessThanOrEqualTo(properties.outbox().maxAttempts());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class))
                .isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class))
                .isEqualTo(COMMANDS);
        assertThat(admission.snapshot(sku).confirmed()).isEqualTo(COMMANDS);
        assertThat(metric("RETRY")).isGreaterThanOrEqualTo(1);
        assertThat(metric("RECEIVED")).isGreaterThan(COMMANDS);
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

    private double metric(String outcome) {
        var counter = meters.find("flashflow.messaging.delivery").tag("outcome", outcome).counter();
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
