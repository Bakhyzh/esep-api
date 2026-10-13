package com.esep.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    @Test
    void eventWithoutCurrency_isInvalid() {
        String payload = """
                {"eventId": "6f1c2b8e-6c1d-4c7a-9a55-0b6f3e2d9a10", "transactionId": 5,
                 "fromAccountId": 10, "fromUserId": 1, "toAccountId": 20, "toUserId": 2, "amount": 100}
                """;

        assertThatThrownBy(() -> listener.onMessage(record(payload)))
                .isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(notificationService);
    }

    @Test
    void completeEvent_isHandedToTheService() {
        String payload = """
                {"eventId": "6f1c2b8e-6c1d-4c7a-9a55-0b6f3e2d9a10", "transactionId": 5,
                 "fromAccountId": 10, "fromUserId": 1, "toAccountId": 20, "toUserId": 2,
                 "amount": 100, "currency": "KZT", "occurredAt": "2026-03-01T10:00:00Z"}
                """;

        listener.onMessage(record(payload));

        verify(notificationService).onTransferCompleted(any());
    }

    static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("esep.transfers", 0, 0L, "10", value);
    }
}
