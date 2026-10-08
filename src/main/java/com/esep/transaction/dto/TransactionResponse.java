package com.esep.transaction.dto;

import com.esep.transaction.EntryDirection;
import com.esep.transaction.LedgerEntry;
import com.esep.transaction.LedgerTransaction;
import com.esep.transaction.TransactionStatus;
import com.esep.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionResponse(
        Long id,
        TransactionType type,
        TransactionStatus status,
        Instant createdAt,
        List<Entry> entries
) {

    public record Entry(Long accountId, EntryDirection direction, BigDecimal amount) {
    }

    public static TransactionResponse from(LedgerTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getCreatedAt(),
                tx.getEntries().stream()
                        .map(TransactionResponse::toEntry)
                        .toList()
        );
    }

    private static Entry toEntry(LedgerEntry entry) {
        // getId() on a lazy proxy does not hit the database
        return new Entry(entry.getAccount().getId(), entry.getDirection(), entry.getAmount());
    }
}
