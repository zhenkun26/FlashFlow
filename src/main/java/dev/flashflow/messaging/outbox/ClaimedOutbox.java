package dev.flashflow.messaging.outbox;

import dev.flashflow.messaging.persistence.OutboxRow;

public record ClaimedOutbox(OutboxRow row, boolean leaseTakeover) {
}
