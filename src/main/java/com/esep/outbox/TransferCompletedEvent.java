package com.esep.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration event published to Kafka. It is a public contract between services:
 * fields may be added, but never renamed or removed without a new event version.
 */
public record TransferCompletedEvent(
        UUID eventId,
        Long transactionId,
        Long fromAccountId,
        Long fromUserId,
        Long toAccountId,
        Long toUserId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
    public static final String TYPE = "TRANSFER_COMPLETED";
}
