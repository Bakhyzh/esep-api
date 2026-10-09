package com.esep.notification;

import com.esep.outbox.TransferCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class TransferNotificationListener {

    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    // the value is read as a String and parsed here: a broken payload becomes a non-retryable
    // InvalidEventException (straight to the DLT) instead of a deserializer error loop
    @KafkaListener(topics = "${esep.outbox.topic}", groupId = "${esep.kafka.notifications-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        notificationService.onTransferCompleted(parse(record.value()));
    }

    private TransferCompletedEvent parse(String payload) {
        TransferCompletedEvent event;
        try {
            event = jsonMapper.readValue(payload, TransferCompletedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Malformed TransferCompletedEvent", e);
        }
        if (event == null || event.eventId() == null || event.fromUserId() == null
                || event.toUserId() == null || event.amount() == null) {
            throw new InvalidEventException("TransferCompletedEvent misses required fields", null);
        }
        return event;
    }
}
