package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.outbox.OutboxBacklog;
import dev.flashflow.messaging.outbox.OutboxStore;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.support.RedisIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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
class V4OutboxBacklogDrainIntegrationTest extends RedisIntegrationTest {
    private static final int COMMANDS = 12;
    private static final String RUN = UUID.randomUUID().toString();
    @Autowired private TestRestTemplate http;
    @Autowired private CommandLedgerService ledger;
    @Autowired private AdmissionAdministrationPort admission;
    @Autowired private OutboxStore outbox;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void outboxMessaging(DynamicPropertyRegistry registry) {
        registry.add("flashflow.messaging.mode", () -> "OUTBOX");
        registry.add("flashflow.messaging.namesrv-addr", () -> "127.0.0.1:9876");
        registry.add("flashflow.messaging.order-topic", () -> "flashflow-order-command-v4-backlog");
        registry.add("flashflow.messaging.order-consumer-group", () -> "flashflow-v4-backlog-orders");
        registry.add("flashflow.messaging.expiration-consumer-group", () -> "flashflow-v4-backlog-exp");
        registry.add("flashflow.messaging.consume-from", () -> "FIRST");
        registry.add("flashflow.messaging.outbox.batch-size", () -> 4);
        registry.add("flashflow.messaging.outbox.poll-interval", () -> "100ms");
        registry.add("flashflow.messaging.outbox.initial-backoff", () -> "100ms");
        registry.add("flashflow.messaging.outbox.max-backoff", () -> "1s");
        registry.add("flashflow.messaging.outbox.max-attempts", () -> 64);
        registry.add("flashflow.messaging.outbox.lease-duration", () -> "10s");
        registry.add("flashflow.messaging.outbox.lease-owner", () -> "v4-backlog-" + RUN);
        registry.add("flashflow.expiration.order-ttl", () -> "PT30S");
        registry.add("flashflow.expiration.scheduling-enabled", () -> false);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void boundedBacklogDrainsAfterRecoveryWithAttributedLocalEvidence() throws Exception {
        Instant runStarted = Instant.now();
        String suffix = RUN.substring(0, 8);
        String sku = "sku-v4-backlog-" + suffix;
        fixture().activeSku("activity-v4-backlog-" + suffix, sku, COMMANDS);
        String generation = "g-" + RUN;
        assertThat(admission.beginGeneration(sku, generation, COMMANDS, "fence-" + suffix)).isTrue();
        assertThat(admission.publishGeneration(sku, generation, "fence-" + suffix)).isTrue();

        var docker = DockerClientFactory.instance().client();
        var matches = docker.listContainersCmd().withShowAll(true)
                .withNameFilter(java.util.List.of("flashflow-rocketmq-broker-1")).exec();
        assertThat(matches).hasSize(1);
        String brokerId = matches.getFirst().getId();
        docker.stopContainerCmd(brokerId).withTimeout(10).exec();
        await(Duration.ofSeconds(10), () -> !Boolean.TRUE.equals(
                docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));

        List<String> commandIds = new ArrayList<>();
        List<Long> acceptanceMillis = new ArrayList<>();
        Instant recoveryStarted;
        OutboxBacklog accumulated;
        try {
            for (int index = 0; index < COMMANDS; index++) {
                long started = System.nanoTime();
                commandIds.add(post("key-" + suffix + "-" + index,
                        "user-" + suffix + "-" + index, sku).commandId());
                acceptanceMillis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
            accumulated = new OutboxBacklog(
                    jdbc.queryForObject("""
                            SELECT COUNT(*) FROM order_command_outbox WHERE status <> 'ACKNOWLEDGED'
                            """, Long.class),
                    jdbc.queryForObject("""
                            SELECT COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND, created_at, NOW(6)) / 1000), 0)
                            FROM order_command_outbox WHERE status <> 'ACKNOWLEDGED'
                            """, Long.class));
            assertThat(accumulated.ready()).isEqualTo(COMMANDS);
        } finally {
            recoveryStarted = Instant.now();
            docker.startContainerCmd(brokerId).exec();
            await(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(
                    docker.inspectContainerCmd(brokerId).exec().getState().getRunning()));
        }

        await(Duration.ofSeconds(60), () -> commandIds.stream().allMatch(commandId ->
                ledger.summary(commandId).status() == CommandStatus.COMPLETED)
                && acknowledgedCount() == COMMANDS,
                () -> recoveryDiagnostics(commandIds));
        long drainMillis = Duration.between(recoveryStarted, Instant.now()).toMillis();
        long attempts = jdbc.queryForObject("SELECT COALESCE(SUM(attempt_count), 0) FROM order_command_outbox",
                Long.class);
        long deliveries = Math.round(metric("RECEIVED"));
        long duplicates = Math.max(0, deliveries - COMMANDS);
        long brokerAckP95 = percentile(jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND, created_at, acknowledged_at) / 1000
                FROM order_command_outbox ORDER BY outbox_id
                """, Long.class), 0.95);
        long completionP95 = percentile(jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) / 1000
                FROM order_command_ledger WHERE status = 'COMPLETED' ORDER BY command_id
                """, Long.class), 0.95);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation", Integer.class))
                .isEqualTo(COMMANDS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement", Integer.class))
                .isEqualTo(COMMANDS);
        var snapshot = admission.snapshot(sku);
        assertThat(snapshot.remainingCapacity()).isZero();
        assertThat(snapshot.held()).isZero();
        assertThat(snapshot.confirmed()).isEqualTo(COMMANDS);
        assertThat(outbox.backlog().ready()).isZero();

        writeEvidence(new Evidence(
                Duration.between(runStarted, Instant.now()).toMillis(), percentile(acceptanceMillis, 0.95),
                brokerAckP95, completionP95, drainMillis, accumulated.ready(), accumulated.oldestAgeMillis(),
                attempts, Math.max(0, attempts - COMMANDS), deliveries, duplicates,
                snapshot.remainingCapacity(), snapshot.held(), snapshot.confirmed()));
    }

    private void writeEvidence(Evidence evidence) throws IOException {
        String configured = System.getProperty("flashflow.v4.report-dir");
        if (configured == null || configured.isBlank()) return;
        Path report = Path.of(configured).resolve("backlog-drain.properties");
        String content = """
                scenario=broker-outage-backlog-drain
                datasetCommands=%d
                concurrency=4
                faultSchedule=broker-stop-before-acceptance,start-after-bounded-backlog
                durationMillis=%d
                acceptanceP95Millis=%d
                brokerAcknowledgementP95Millis=%d
                completionP95Millis=%d
                backlogDrainMillis=%d
                maximumBacklog=%d
                oldestBacklogAgeMillis=%d
                publicationAttempts=%d
                retries=%d
                deliveries=%d
                duplicateDeliveries=%d
                finalRemainingCapacity=%d
                finalHeld=%d
                finalConfirmed=%d
                commandInvariant=PASS
                outboxInvariant=PASS
                orderInvariant=PASS
                claimInvariant=PASS
                reservationInvariant=PASS
                movementInvariant=PASS
                admissionInvariant=PASS
                evidenceBoundary=local-disposable-topology-not-production-capacity-or-sla
                """.formatted(COMMANDS, evidence.durationMillis(), evidence.acceptanceP95Millis(),
                evidence.brokerAckP95Millis(), evidence.completionP95Millis(), evidence.backlogDrainMillis(),
                evidence.maximumBacklog(), evidence.oldestBacklogAgeMillis(), evidence.publicationAttempts(),
                evidence.retries(), evidence.deliveries(), evidence.duplicateDeliveries(),
                evidence.remainingCapacity(), evidence.held(), evidence.confirmed());
        Files.writeString(report, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
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

    private long acknowledgedCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_command_outbox WHERE status = 'ACKNOWLEDGED'", Long.class);
    }

    private String recoveryDiagnostics(List<String> commandIds) {
        var commandStatuses = commandIds.stream()
                .map(commandId -> commandId.substring(0, 8) + "=" + ledger.summary(commandId).status())
                .toList();
        var outboxStatuses = jdbc.queryForList("""
                SELECT status, COUNT(*) AS row_count
                FROM order_command_outbox
                GROUP BY status
                ORDER BY status
                """);
        return "commandStatuses=" + commandStatuses + ", outboxStatuses=" + outboxStatuses;
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static void await(Duration timeout, CheckedBoolean condition) throws Exception {
        await(timeout, condition, () -> "no diagnostics available");
    }

    private static void await(Duration timeout, CheckedBoolean condition, Supplier<String> diagnostics)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("condition did not pass within " + timeout + ": " + diagnostics.get());
    }

    private record Evidence(
            long durationMillis,
            long acceptanceP95Millis,
            long brokerAckP95Millis,
            long completionP95Millis,
            long backlogDrainMillis,
            long maximumBacklog,
            long oldestBacklogAgeMillis,
            long publicationAttempts,
            long retries,
            long deliveries,
            long duplicateDeliveries,
            long remainingCapacity,
            long held,
            long confirmed) {}

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
