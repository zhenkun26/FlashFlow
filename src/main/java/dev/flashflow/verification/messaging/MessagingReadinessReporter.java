package dev.flashflow.verification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.flashflow.messaging.ReadinessStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MessagingReadinessReporter {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MessagingReadinessReporter() {
    }

    public static MessagingReadinessReport validate(MessagingReadinessReport report) {
        boolean gatesPass = report.gates() != null && !report.gates().isEmpty()
                && report.gates().values().stream().allMatch(status -> status == ReadinessStatus.PASS);
        boolean inputsPresent = text(report.runId()) && text(report.gitRevision()) && text(report.brokerImage())
                && text(report.clientVersion()) && text(report.topology()) && text(report.acknowledgementMode())
                && text(report.delayMechanism()) && report.startedAt() != null && report.endedAt() != null;
        boolean countsPass = report.counts() != null && report.counts().reconciles();
        boolean blocked = report.gates() != null
                && report.gates().values().stream().anyMatch(status -> status == ReadinessStatus.BLOCKED);
        boolean notRun = report.gates() != null
                && report.gates().values().stream().anyMatch(status -> status == ReadinessStatus.NOT_RUN);
        ReadinessStatus expected = !inputsPresent || blocked ? ReadinessStatus.BLOCKED
                : notRun ? ReadinessStatus.NOT_RUN
                : gatesPass && countsPass ? ReadinessStatus.PASS : ReadinessStatus.FAIL;
        if (report.status() != expected) {
            throw new IllegalArgumentException("Messaging readiness status must be " + expected);
        }
        return report;
    }

    public static void write(Path directory, MessagingReadinessReport report) throws IOException {
        validate(report);
        Files.createDirectories(directory);
        Path json = directory.resolve("readiness.json");
        Path markdown = directory.resolve("report.md");
        if (Files.exists(json) || Files.exists(markdown)) {
            throw new IllegalStateException("Refusing to overwrite messaging readiness evidence");
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
        Files.writeString(markdown, markdown(report), StandardCharsets.UTF_8);
    }

    private static String markdown(MessagingReadinessReport report) {
        return "# V2.1 messaging readiness " + report.runId() + "\n\n"
                + "Status: **" + report.status() + "**\n\n"
                + "- Revision: `" + report.gitRevision() + "`\n"
                + "- Broker: `" + report.brokerImage() + "`\n"
                + "- Client/tooling: `" + report.clientVersion() + "`\n"
                + "- Topology: `" + report.topology() + "`\n"
                + "- Counts reconcile: `" + report.counts().reconciles() + "`\n\n"
                + "Local spike evidence is not a production reliability, delay-SLA, or capacity claim.\n";
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
