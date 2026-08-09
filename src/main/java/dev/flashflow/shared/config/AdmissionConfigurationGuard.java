package dev.flashflow.shared.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public final class AdmissionConfigurationGuard {
    private final FlashFlowProperties properties;

    public AdmissionConfigurationGuard(FlashFlowProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        FlashFlowProperties.Admission admission = properties.admission();
        if (admission.heldResolution().compareTo(Duration.ofSeconds(1)) < 0
                || admission.heldResolution().compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("Admission held-resolution must be between PT1S and PT1H");
        }
        if (admission.mode() == FlashFlowProperties.AdmissionMode.REDIS_LUA
                && (admission.identitySecret() == null || admission.identitySecret().length() < 32)) {
            throw new IllegalStateException(
                    "REDIS_LUA admission requires FLASHFLOW_ADMISSION_IDENTITY_SECRET with at least 32 characters");
        }
    }
}
