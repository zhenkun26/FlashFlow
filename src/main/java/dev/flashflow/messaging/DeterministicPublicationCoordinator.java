package dev.flashflow.messaging;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.shared.FlashFlowMetrics;
import org.springframework.stereotype.Component;

@Component
public final class DeterministicPublicationCoordinator {
    private final AdmissionPort admission;
    private final FlashFlowMetrics metrics;

    public DeterministicPublicationCoordinator(AdmissionPort admission, FlashFlowMetrics metrics) {
        this.admission = admission;
        this.metrics = metrics;
    }

    public PublicationResolution resolve(
            AdmissionCommand command, String generation, PublicationResult publication) {
        PublicationResolution resolution = switch (publication.outcome()) {
            case BROKER_ACKNOWLEDGED -> PublicationResolution.RETAINED;
            case DEFINITELY_NOT_PUBLISHED -> release(command, generation);
            case AMBIGUOUS -> quarantine(command, generation);
        };
        metrics.command("PUBLICATION", publication.outcome().name());
        return resolution;
    }

    private PublicationResolution release(AdmissionCommand command, String generation) {
        AdmissionLifecycleResult result = admission.release(command, generation, false);
        if (result.decision() == AdmissionLifecycleDecision.RELEASED
                || result.decision() == AdmissionLifecycleDecision.ALREADY_RELEASED) {
            return PublicationResolution.RELEASED;
        }
        return quarantine(command, generation);
    }

    private PublicationResolution quarantine(AdmissionCommand command, String generation) {
        admission.quarantine(command, generation);
        return PublicationResolution.QUARANTINED;
    }
}
