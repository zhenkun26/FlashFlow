package dev.flashflow.expiration;

import dev.flashflow.shared.config.FlashFlowProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ExpirationRunner {
    private final ExpirationService expirationService;
    private final FlashFlowProperties properties;

    public ExpirationRunner(ExpirationService expirationService, FlashFlowProperties properties) {
        this.expirationService = expirationService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${flashflow.expiration.scan-delay:PT1S}")
    public void scheduledRun() {
        if (properties.expiration().schedulingEnabled()) {
            expirationService.expireBatch();
        }
    }

    public int runOnce() {
        return expirationService.expireBatch();
    }
}

