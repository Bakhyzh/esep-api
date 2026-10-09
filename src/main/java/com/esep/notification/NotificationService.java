package com.esep.notification;

import com.esep.notification.dto.NotificationResponse;
import com.esep.outbox.TransferCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String CONSUMER = "transfer-notifications";

    private final NotificationRepository repository;

    /**
     * Idempotent: the event id is recorded in processed_events in the SAME transaction
     * as the notifications. A redelivered event finds its id there and is skipped;
     * if anything fails, both the id and the notifications roll back and the retry starts clean.
     */
    @Transactional
    public void onTransferCompleted(TransferCompletedEvent event) {
        if (!repository.markProcessed(CONSUMER, event.eventId())) {
            log.info("Duplicate event {} skipped", event.eventId());
            return;
        }
        String amount = event.amount().stripTrailingZeros().toPlainString() + " " + event.currency();
        repository.insert(event.fromUserId(), event.eventId(), NotificationType.TRANSFER_SENT,
                "You sent " + amount + " from account " + event.fromAccountId() + " to account " + event.toAccountId());
        if (!event.toUserId().equals(event.fromUserId())) {
            repository.insert(event.toUserId(), event.eventId(), NotificationType.TRANSFER_RECEIVED,
                    "You received " + amount + " on account " + event.toAccountId());
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> latest(long userId, int limit) {
        return repository.findLatest(userId, limit);
    }
}
