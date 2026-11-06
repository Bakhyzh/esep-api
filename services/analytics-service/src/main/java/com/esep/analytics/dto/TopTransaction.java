package com.esep.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** rank: equal amounts share a rank (DENSE_RANK). */
public record TopTransaction(int rank, Long transactionId, BigDecimal amount, Long toAccountId, Instant createdAt) {
}
