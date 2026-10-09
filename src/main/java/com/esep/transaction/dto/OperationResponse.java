package com.esep.transaction.dto;

import com.esep.transaction.EntryDirection;
import com.esep.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of the user's statement: a ledger entry on one of their accounts.
 * counterpartyAccountId is the other side of the transaction (for a deposit: the system account).
 */
public record OperationResponse(
        Long entryId,
        Long transactionId,
        TransactionType type,
        EntryDirection direction,
        BigDecimal amount,
        String currency,
        Long accountId,
        Long counterpartyAccountId,
        Instant createdAt
) {
}
