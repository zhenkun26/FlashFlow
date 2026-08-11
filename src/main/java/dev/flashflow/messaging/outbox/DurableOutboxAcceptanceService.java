package dev.flashflow.messaging.outbox;

import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.messaging.persistence.OutboxRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DurableOutboxAcceptanceService {
    private final CommandLedgerService ledger;
    private final OutboxStore outbox;
    private final OutboxAcceptanceTransactionHook hook;

    public DurableOutboxAcceptanceService(CommandLedgerService ledger, OutboxStore outbox,
                                          OutboxAcceptanceTransactionHook hook) {
        this.ledger = ledger;
        this.outbox = outbox;
        this.hook = hook;
    }

    @Transactional
    public OutboxAcceptance accept(OrderCommandEnvelope envelope, String topic, String tag) {
        CommandRow command = ledger.prepare(envelope);
        hook.afterCommandPrepared(envelope);
        OutboxRow publication = outbox.prepare(envelope, topic, tag);
        hook.afterOutboxPrepared(envelope);
        command = ledger.markPublication(envelope.commandId(), CommandStatus.ACCEPTED, "OUTBOX_COMMITTED");
        return new OutboxAcceptance(command, publication);
    }

    @Transactional(readOnly = true)
    public OutboxAcceptance replay(OrderCommandEnvelope envelope, String topic, String tag) {
        CommandRow command = ledger.require(envelope.commandId());
        OutboxRow publication = outbox.requireByCommand(envelope.commandId());
        outbox.verify(publication, envelope, topic, tag);
        return new OutboxAcceptance(command, publication);
    }
}
