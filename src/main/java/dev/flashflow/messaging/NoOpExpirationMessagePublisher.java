package dev.flashflow.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${flashflow.messaging.mode:DISABLED}' != 'DIRECT' and '${flashflow.messaging.mode:DISABLED}' != 'OUTBOX'")
public final class NoOpExpirationMessagePublisher implements ExpirationMessagePublisher {
    @Override
    public PublicationResult publish(DelayedExpirationEnvelope envelope) {
        return new PublicationResult(PublicationOutcome.DEFINITELY_NOT_PUBLISHED, "MESSAGING_DISABLED");
    }
}
