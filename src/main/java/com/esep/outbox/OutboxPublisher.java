package com.esep.outbox;

import com.esep.outbox.OutboxRepository.OutboxEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and sends events to Kafka.
 * Delivery is at-least-once: if the app dies after Kafka acknowledged the send but before
 * published_at is committed, the event is sent again - consumers must be idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "esep.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties, TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${esep.outbox.poll-interval}")
    public void publishPending() {
        Integer published = transactionTemplate.execute(status -> publishBatch());
        if (published != null && published > 0) {
            log.debug("Published {} outbox events", published);
        }
    }

    /** Runs inside one DB transaction: the row locks are held until published_at is committed. */
    int publishBatch() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(properties.batchSize());
        List<Long> published = new ArrayList<>();
        for (OutboxEvent event : batch) {
            try {
                send(event);
                published.add(event.id());
            } catch (Exception e) {
                // stop at the first failure: sending later events first would break per-key ordering
                log.warn("Outbox event {} not published, will retry: {}", event.eventId(), e.toString());
                outboxRepository.markFailed(event.id(), e.toString());
                break;
            }
        }
        if (!published.isEmpty()) {
            outboxRepository.markPublished(published);
        }
        return published.size();
    }

    private void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.topic(), event.messageKey(), event.payload());
        record.headers().add(EVENT_ID_HEADER, event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, event.eventType().getBytes(StandardCharsets.UTF_8));
        // wait for the broker ack (acks=all): only then is it safe to mark the row as published
        kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
