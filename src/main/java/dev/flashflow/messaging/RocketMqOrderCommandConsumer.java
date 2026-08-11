package dev.flashflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${flashflow.messaging.mode:DISABLED}' == 'DIRECT' or '${flashflow.messaging.mode:DISABLED}' == 'OUTBOX'")
public final class RocketMqOrderCommandConsumer implements MessageListenerConcurrently {
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderCommandConsumer consumer;
    private final DeadLetterPublisher deadLetters;
    private final CommandLedgerService ledger;
    private final FlashFlowMetrics metrics;
    private final MessagingFaultInjector faults;
    private DefaultMQPushConsumer rocketConsumer;

    public RocketMqOrderCommandConsumer(
            MessagingProperties properties,
            ObjectMapper objectMapper,
            OrderCommandConsumer consumer,
            DeadLetterPublisher deadLetters,
            CommandLedgerService ledger,
            FlashFlowMetrics metrics,
            MessagingFaultInjector faults) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.consumer = consumer;
        this.deadLetters = deadLetters;
        this.ledger = ledger;
        this.metrics = metrics;
        this.faults = faults;
    }

    @PostConstruct
    void start() throws MQClientException {
        rocketConsumer = new DefaultMQPushConsumer(properties.orderConsumerGroup());
        rocketConsumer.setNamesrvAddr(properties.namesrvAddr());
        rocketConsumer.setConsumeFromWhere(properties.consumeFrom() == MessagingProperties.ConsumeStart.FIRST
                ? ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET : ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        // Keep one Broker delivery beyond the application retry bound so the listener can
        // publish the bounded, inspectable FlashFlow DLQ record itself.
        rocketConsumer.setMaxReconsumeTimes(properties.maxReconsumeTimes() + 1);
        rocketConsumer.subscribe(properties.orderTopic(), "ORDER_V1");
        rocketConsumer.registerMessageListener(this);
        rocketConsumer.start();
    }

    @PreDestroy
    void stop() {
        if (rocketConsumer != null) rocketConsumer.shutdown();
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            ConsumeConcurrentlyStatus status = consumeOne(message);
            if (status != ConsumeConcurrentlyStatus.CONSUME_SUCCESS) return status;
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus consumeOne(MessageExt message) {
        metrics.delivery("RECEIVED");
        OrderCommandEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), OrderCommandEnvelope.class);
        } catch (Exception invalid) {
            return deadLetter(message, "INVALID_ENVELOPE", 0);
        }
        try {
            faults.beforeConsume();
            CommandConsumptionResult result = consumer.consume(envelope);
            if (result.acknowledgementEligible()) {
                faults.afterDurableResultBeforeAcknowledgement(envelope.commandId(), result);
                metrics.delivery("ACKNOWLEDGED");
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            return retryOrDeadLetter(message, envelope.schemaVersion(), result.outcome().name());
        } catch (CommandConflictException conflict) {
            return deadLetter(message, "CONFLICTING_PAYLOAD", envelope.schemaVersion());
        } catch (IllegalArgumentException invalid) {
            return deadLetter(message, "UNSUPPORTED_ENVELOPE", envelope.schemaVersion());
        } catch (RuntimeException failure) {
            return retryOrDeadLetter(message, envelope.schemaVersion(), "PROCESSING_FAILURE");
        }
    }

    private ConsumeConcurrentlyStatus retryOrDeadLetter(MessageExt message, int schemaVersion, String reason) {
        if (message.getReconsumeTimes() >= properties.maxReconsumeTimes()) {
            return deadLetter(message, "RETRY_EXHAUSTED_" + reason, schemaVersion);
        }
        metrics.delivery("RETRY");
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }

    private ConsumeConcurrentlyStatus deadLetter(MessageExt message, String reason, int schemaVersion) {
        String key = message.getKeys();
        if (key == null || key.isBlank()) key = "unknown-command";
        boolean published = deadLetters.publish(message.getTopic(), key, schemaVersion,
                message.getReconsumeTimes() + 1, reason, message.getBody());
        if (published && key.matches("[0-9a-f]{64}")) {
            try {
                ledger.markDeadLetter(key, reason);
            } catch (IllegalArgumentException ignored) {
                metrics.delivery("DEAD_LETTER_UNKNOWN_COMMAND");
            }
        }
        return published ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS
                : ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }
}
