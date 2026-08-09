package dev.flashflow.messaging;

import java.net.URI;

public final class FutureAsyncOrderContract {
    private FutureAsyncOrderContract() {
    }

    public record Accepted(int httpStatus, String commandId, URI statusLocation, String orderId) {
        public Accepted {
            if (httpStatus != 202) throw new IllegalArgumentException("Asynchronous acceptance must use HTTP 202");
            if (commandId == null || commandId.isBlank()) throw new IllegalArgumentException("commandId is required");
            if (statusLocation == null) throw new IllegalArgumentException("statusLocation is required");
            if (orderId != null) throw new IllegalArgumentException("Acceptance cannot claim an order result");
        }
    }
}
