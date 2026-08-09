package dev.flashflow.messaging;

public final class CommandConflictException extends RuntimeException {
    public CommandConflictException(String message) {
        super(message);
    }
}
