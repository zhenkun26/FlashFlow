package dev.flashflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.expiration.ExpirationService;
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
public final class RocketMqExpirationConsumer implements MessageListenerConcurrently {
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final ExpirationService expiration;
    private final FlashFlowMetrics metrics;
    private final MessagingFaultInjector faults;
    private DefaultMQPushConsumer rocketConsumer;

    public RocketMqExpirationConsumer(
            MessagingProperties properties, ObjectMapper objectMapper,
            ExpirationService expiration, FlashFlowMetrics metrics, MessagingFaultInjector faults) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.expiration = expiration;
        this.metrics = metrics;
        this.faults = faults;
    }

    @PostConstruct
    void start() throws MQClientException {
        rocketConsumer = new DefaultMQPushConsumer(properties.expirationConsumerGroup());
        rocketConsumer.setNamesrvAddr(properties.namesrvAddr());
        rocketConsumer.setConsumeFromWhere(properties.consumeFrom() == MessagingProperties.ConsumeStart.FIRST
                ? ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET : ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        rocketConsumer.setMaxReconsumeTimes(properties.maxReconsumeTimes());
        rocketConsumer.subscribe(properties.expirationTopic(), "EXPIRATION_V1");
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
            try {
                DelayedExpirationEnvelope envelope = objectMapper.readValue(
                        message.getBody(), DelayedExpirationEnvelope.class);
                ExpirationTriggerOutcome outcome = expiration.expireOne(envelope);
                metrics.expirationTrigger("CONSUME_" + outcome.name());
                faults.afterExpirationResultBeforeAcknowledgement(envelope.orderId(), outcome);
                if (outcome == ExpirationTriggerOutcome.TOO_EARLY
                        && message.getReconsumeTimes() < properties.maxReconsumeTimes()) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            } catch (RuntimeException failure) {
                metrics.expirationTrigger("CONSUME_RETRY");
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (Exception invalid) {
                metrics.expirationTrigger("CONSUME_REJECTED");
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
