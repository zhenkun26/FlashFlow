package dev.flashflow.messaging;

public interface DeadLetterPublisher {
    boolean publish(String sourceTopic, String messageKey, int schemaVersion, int attempts,
                    String reason, byte[] originalBody);
}
