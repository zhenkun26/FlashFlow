package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.shared.FlashFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeterministicPublicationCoordinatorTest {
    @Test
    void definitiveFailureReleasesOnceAndAcknowledgementRetains() {
        FakeAdmission admission = new FakeAdmission();
        DeterministicPublicationCoordinator coordinator = coordinator(admission);
        assertThat(coordinator.resolve(command(), "g1", result(PublicationOutcome.DEFINITELY_NOT_PUBLISHED)))
                .isEqualTo(PublicationResolution.RELEASED);
        assertThat(coordinator.resolve(command(), "g1", result(PublicationOutcome.DEFINITELY_NOT_PUBLISHED)))
                .isEqualTo(PublicationResolution.RELEASED);
        assertThat(admission.releaseEffects).isEqualTo(1);
        assertThat(coordinator.resolve(command(), "g1", result(PublicationOutcome.BROKER_ACKNOWLEDGED)))
                .isEqualTo(PublicationResolution.RETAINED);
        assertThat(admission.releaseEffects).isEqualTo(1);
    }

    @Test
    void ambiguousPublicationQuarantinesWithoutRelease() {
        FakeAdmission admission = new FakeAdmission();
        assertThat(coordinator(admission).resolve(command(), "g1", result(PublicationOutcome.AMBIGUOUS)))
                .isEqualTo(PublicationResolution.QUARANTINED);
        assertThat(admission.releaseEffects).isZero();
        assertThat(admission.quarantines).isEqualTo(1);
    }

    private static DeterministicPublicationCoordinator coordinator(AdmissionPort admission) {
        return new DeterministicPublicationCoordinator(admission,
                new FlashFlowMetrics(new SimpleMeterRegistry()));
    }

    private static PublicationResult result(PublicationOutcome outcome) {
        return new PublicationResult(outcome, "injected");
    }

    private static AdmissionCommand command() {
        return new AdmissionCommand("sku", "admission", "user-digest", Instant.now());
    }

    private static final class FakeAdmission implements AdmissionPort {
        private int releaseEffects;
        private int quarantines;
        private boolean released;

        @Override public AdmissionResult acquire(AdmissionCommand command) {
            return new AdmissionResult(AdmissionDecision.ADMITTED, command.admissionId(), "g1");
        }
        @Override public AdmissionLifecycleResult confirm(AdmissionCommand command, String generation) {
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.CONFIRMED, generation);
        }
        @Override public AdmissionLifecycleResult release(
                AdmissionCommand command, String generation, boolean confirmedClosure) {
            if (released) return new AdmissionLifecycleResult(AdmissionLifecycleDecision.ALREADY_RELEASED, generation);
            released = true;
            releaseEffects++;
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.RELEASED, generation);
        }
        @Override public AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation) {
            quarantines++;
            return new AdmissionLifecycleResult(AdmissionLifecycleDecision.QUARANTINED, generation);
        }
    }
}
