package dev.flashflow.messaging.outbox;

import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.messaging.persistence.OutboxRow;

public record OutboxAcceptance(CommandRow command, OutboxRow outbox) {
}
