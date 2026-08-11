package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MessagingBoundaryTest {
    @Test
    void v3PinsRocketMqAndKeepsLiveComponentsConditional() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String controllers;
        try (var paths = Files.walk(Path.of("src/main/java/dev/flashflow"))) {
            controllers = paths.filter(path -> path.toString().endsWith("Controller.java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).reduce("", String::concat);
        }
        assertThat(pom).contains("org.apache.rocketmq", "<rocketmq.client.version>5.3.3");
        assertThat(application).contains("mode: ${FLASHFLOW_MESSAGING_MODE:DISABLED}");
        assertThat(controllers).contains("/api/v2", "ConditionalOnExpression", "'DIRECT'", "'OUTBOX'");
        assertThat(Files.readString(Path.of(
                "src/main/java/dev/flashflow/messaging/RocketMqOrderCommandPublisher.java")))
                .contains("ConditionalOnExpression", "'DIRECT'", "'OUTBOX'");
        String direct = Files.readString(Path.of(
                "src/main/java/dev/flashflow/messaging/AsyncOrderApplicationService.java"));
        assertThat(direct).contains("havingValue = \"DIRECT\"")
                .doesNotContain("OutboxStore", "DurableOutboxAcceptanceService");
        String synchronous = Files.readString(Path.of(
                "src/main/java/dev/flashflow/ordering/web/OrderController.java"));
        assertThat(synchronous).contains("/api/v1/orders")
                .doesNotContain("ConditionalOnProperty", "ConditionalOnExpression");
    }

    @Test
    void v4BoundaryPermitsPollingOutboxWithoutCdcOrExternalPublicationAuthority() throws Exception {
        String pom = Files.readString(Path.of("pom.xml")).toLowerCase();
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V4__create_order_command_outbox.sql")).toLowerCase();
        String production;
        try (var paths = Files.walk(Path.of("src/main/java/dev/flashflow"))) {
            production = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).reduce("", String::concat).toLowerCase();
        }

        assertThat(pom).doesNotContain("debezium", "kafka-connect", "spring-kafka", "redisson");
        assertThat(production).doesNotContain("debezium", "kafkaconnect", "binlogreader",
                "deadletterreplay", "redisoutbox", "redispublication");
        assertThat(migration).contains("create table order_command_outbox")
                .doesNotContain("insert into order_command_outbox", "select * from order_command_ledger");
    }
}
