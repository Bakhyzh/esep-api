package com.esep.notification;

import com.esep.outbox.TransferCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    @InjectMocks
    private NotificationService service;

    @Test
    void newEvent_notifiesSenderAndReceiver() {
        TransferCompletedEvent event = event(1L, 2L);
        when(repository.markProcessed(NotificationService.CONSUMER, event.eventId())).thenReturn(true);

        service.onTransferCompleted(event);

        verify(repository).insert(eq(1L), eq(event.eventId()), eq(NotificationType.TRANSFER_SENT), anyString());
        verify(repository).insert(eq(2L), eq(event.eventId()), eq(NotificationType.TRANSFER_RECEIVED), anyString());
    }

    @Test
    void duplicateEvent_isSkipped() {
        TransferCompletedEvent event = event(1L, 2L);
        when(repository.markProcessed(NotificationService.CONSUMER, event.eventId())).thenReturn(false);

        service.onTransferCompleted(event);

        verify(repository, never()).insert(anyLong(), any(), any(), anyString());
    }

    private static TransferCompletedEvent event(Long fromUser, Long toUser) {
        return new TransferCompletedEvent(UUID.randomUUID(), 5L, 10L, fromUser, 20L, toUser,
                new BigDecimal("100.2500"), "KZT", Instant.now());
    }
}
