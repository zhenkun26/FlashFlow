package dev.flashflow.messaging;

import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.FlashFlowMetrics;
import org.springframework.stereotype.Service;

@Service
public final class InProcessOrderCommandConsumer implements OrderCommandConsumer {
    private final CommandLedgerService ledger;
    private final OrderCommandExecutor ordering;
    private final FlashFlowMetrics metrics;

    public InProcessOrderCommandConsumer(
            CommandLedgerService ledger, OrderCommandExecutor ordering, FlashFlowMetrics metrics) {
        this.ledger = ledger;
        this.ordering = ordering;
        this.metrics = metrics;
    }

    @Override
    public CommandConsumptionResult consume(OrderCommandEnvelope envelope) {
        metrics.command("DELIVERY", "RECEIVED");
        CommandRow row;
        try {
            row = ledger.prepare(envelope);
        } catch (CommandConflictException conflict) {
            metrics.command("DELIVERY", DeliveryOutcome.CONFLICTING_PAYLOAD.name());
            return new CommandConsumptionResult(envelope.commandId(), CommandStatus.REJECTED,
                    OrderResultCode.IDEMPOTENCY_CONFLICT.name(), null, ConsumerOutcome.REJECTED, true);
        }
        if (isTerminal(row)) return from(row);
        if (!ledger.claim(envelope.commandId())) return from(ledger.require(envelope.commandId()));

        try {
            OrderResult result = ordering.execute(new PlaceOrderCommand(
                    envelope.callerId(), envelope.activitySkuId(), envelope.idempotencyKey()));
            if (result.code() == OrderResultCode.RETRYABLE_CONTENTION) {
                row = ledger.mark(envelope.commandId(), CommandStatus.RETRYABLE);
                metrics.command("CONSUME", ConsumerOutcome.RETRY.name());
                return from(row);
            }
            CommandStatus terminal = switch (result.code()) {
                case CREATED, EXISTING_EFFECTIVE_ORDER -> CommandStatus.COMPLETED;
                default -> CommandStatus.REJECTED;
            };
            row = ledger.finish(envelope.commandId(), terminal, result.code().name(), result.orderId());
            metrics.command("CONSUME", ConsumerOutcome.ACKNOWLEDGED.name());
            return from(row);
        } catch (RuntimeException failure) {
            ledger.mark(envelope.commandId(), CommandStatus.UNRESOLVED);
            metrics.command("CONSUME", ConsumerOutcome.UNRESOLVED.name());
            throw failure;
        }
    }

    private static boolean isTerminal(CommandRow row) {
        return CommandStatus.valueOf(row.status()) == CommandStatus.COMPLETED
                || CommandStatus.valueOf(row.status()) == CommandStatus.REJECTED;
    }

    private static CommandConsumptionResult from(CommandRow row) {
        CommandStatus status = CommandStatus.valueOf(row.status());
        boolean terminal = status == CommandStatus.COMPLETED || status == CommandStatus.REJECTED;
        return new CommandConsumptionResult(row.commandId(), status, row.resultCode(), row.orderId(),
                terminal ? ConsumerOutcome.ACKNOWLEDGED : ConsumerOutcome.RETRY, terminal);
    }
}
