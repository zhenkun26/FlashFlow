package dev.flashflow.verification.messaging;

import dev.flashflow.messaging.ReadinessStatus;
import dev.flashflow.messaging.spike.MessagingCounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MessagingReadinessReport(
        String runId,
        String gitRevision,
        Instant startedAt,
        Instant endedAt,
        String brokerImage,
        String clientVersion,
        String topology,
        String acknowledgementMode,
        int producerRetries,
        String delayMechanism,
        Map<String, ReadinessStatus> gates,
        MessagingCounts counts,
        List<String> warnings,
        ReadinessStatus status) {
}
