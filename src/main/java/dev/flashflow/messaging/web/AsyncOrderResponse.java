package dev.flashflow.messaging.web;

import dev.flashflow.messaging.CommandStatus;
import java.net.URI;

public record AsyncOrderResponse(String commandId, CommandStatus status, URI statusLocation, String cause) {
}
