package com.esep.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** Anything that can never be processed must be an InvalidEventException: it goes to the DLT without retries. */
@ExtendWith(MockitoExtension.class)
class TransferNotificationListenerTest {

    @Mock
    private NotificationService notificationService;

    private TransferNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TransferNotificationListener(notificationService, JsonMapper.builder().build());
    }

    @Test
    void tombstoneWithoutValue_isInvalid() {
        assertThatThrownBy(() -> listener.onMessage(record(null)))
                .isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(notificationService);
    }

    @Test
    void malformedJson_isInvalid() {
        assertThatThrownBy(() -> listener.onMessage(record("{not json")))
                .isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(notificationService);
    }

    static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("esep.transfers", 0, 0L, "10", value);
    }
}
