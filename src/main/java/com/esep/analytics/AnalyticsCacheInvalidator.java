package com.esep.analytics;

import com.esep.transaction.TransferCommittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AnalyticsCacheInvalidator {

    private final AnalyticsCache cache;

    /**
     * AFTER_COMMIT: invalidating before the commit would let a parallel request re-cache
     * the old numbers between our invalidation and the commit.
     * Only the sender: spending reports count DEBIT entries, the receiver's spending does not change.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCommitted(TransferCommittedEvent event) {
        cache.invalidateUser(event.senderUserId());
    }
}
