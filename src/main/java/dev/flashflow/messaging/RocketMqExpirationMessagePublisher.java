package dev.flashflow.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public final class RocketMqExpirationMessagePublisher implements ExpirationMessagePublisher {
    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final FlashFlowMetrics metrics;
    private DefaultMQProducer producer;

    public RocketMqExpirationMessagePublisher(
            MessagingProperties properties, ObjectMapper objectMapper, FlashFlowMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() throws MQClientException {
        producer = new DefaultMQProducer(properties.producerGroup() + "-expiration");
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
    public PublicationResult publish(DelayedExpirationEnvelope envelope) {
        try {
            Message message = new Message(properties.expirationTopic(), "EXPIRATION_V1", envelope.orderId(),
                    objectMapper.writeValueAsBytes(envelope));
            message.setDelayTimeLevel(properties.delayLevel());
            SendResult sent = producer.send(message, properties.sendTimeout().toMillis());
            PublicationOutcome outcome = sent != null && sent.getSendStatus() == SendStatus.SEND_OK
                    ? PublicationOutcome.BROKER_ACKNOWLEDGED : PublicationOutcome.AMBIGUOUS;
            metrics.expirationTrigger("PUBLISH_" + outcome.name());
            return new PublicationResult(outcome,
                    sent == null ? "NO_SEND_RESULT" : sent.getSendStatus().name());
        } catch (JsonProcessingException exception) {
            metrics.expirationTrigger("PUBLISH_DEFINITELY_NOT_PUBLISHED");
            return new PublicationResult(PublicationOutcome.DEFINITELY_NOT_PUBLISHED, "SERIALIZATION_FAILED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.expirationTrigger("PUBLISH_AMBIGUOUS");
            return new PublicationResult(PublicationOutcome.AMBIGUOUS, "INTERRUPTED");
        } catch (Exception exception) {
            String cause = exception.getClass().getSimpleName().toUpperCase();
            cause = cause.substring(0, Math.min(64, cause.length()));
            metrics.expirationTrigger("PUBLISH_AMBIGUOUS");
            return new PublicationResult(PublicationOutcome.AMBIGUOUS, cause);
        }
    }
}
