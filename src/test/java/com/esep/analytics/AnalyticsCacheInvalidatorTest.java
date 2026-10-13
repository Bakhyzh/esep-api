package com.esep.analytics;

import com.esep.transaction.TransferCommittedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class AnalyticsCacheInvalidatorTest {

    @Mock
    private AnalyticsCache cache;

    @InjectMocks
    private AnalyticsCacheInvalidator invalidator;

    @Test
    void onlyTheSendersReportsAreInvalidated() {
        invalidator.onTransferCommitted(new TransferCommittedEvent(5L, 1L, 2L));

        // spending reports count DEBIT entries: the receiver's numbers did not change
        verify(cache).invalidateUser(1L);
        verifyNoMoreInteractions(cache);
    }
}
