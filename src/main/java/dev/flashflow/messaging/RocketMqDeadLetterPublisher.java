package dev.flashflow.messaging;

import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${flashflow.messaging.mode:DISABLED}' == 'DIRECT' or '${flashflow.messaging.mode:DISABLED}' == 'OUTBOX'")
public final class RocketMqDeadLetterPublisher implements DeadLetterPublisher {
    private final MessagingProperties properties;
    private final FlashFlowMetrics metrics;
    private DefaultMQProducer producer;

    public RocketMqDeadLetterPublisher(MessagingProperties properties, FlashFlowMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() throws MQClientException {
        producer = new DefaultMQProducer(properties.producerGroup() + "-dead-letter");
        producer.setNamesrvAddr(properties.namesrvAddr());
        producer.setRetryTimesWhenSendFailed(properties.producerRetries());
        producer.setSendMsgTimeout(Math.toIntExact(properties.sendTimeout().toMillis()));
        producer.start();
    }

    @PreDestroy
    void stop() {
        if (producer != null) producer.shutdown();
    }

    @Override
    public boolean publish(String sourceTopic, String messageKey, int schemaVersion, int attempts,
                           String reason, byte[] originalBody) {
        try {
            Message message = new Message(properties.deadLetterTopic(), "ORDER_DLQ", messageKey, originalBody);
            message.putUserProperty("sourceTopic", bounded(sourceTopic));
            message.putUserProperty("schemaVersion", Integer.toString(schemaVersion));
            message.putUserProperty("attempts", Integer.toString(Math.max(0, attempts)));
            message.putUserProperty("reason", bounded(reason));
            SendResult result = producer.send(message, properties.sendTimeout().toMillis());
            boolean acknowledged = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            metrics.delivery(acknowledged ? "DEAD_LETTERED" : "DEAD_LETTER_AMBIGUOUS");
            return acknowledged;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.delivery("DEAD_LETTER_AMBIGUOUS");
            return false;
        } catch (Exception exception) {
            metrics.delivery("DEAD_LETTER_FAILED");
            return false;
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }
}
