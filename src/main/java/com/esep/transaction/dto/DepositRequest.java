package com.esep.transaction.dto;

import com.esep.transaction.RequestFingerprint;
import com.esep.transaction.TransactionType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// temporary open endpoint for filling accounts; in stage 5 it becomes ADMIN-only
public record DepositRequest(
        @NotNull @Positive
        Long accountId,

        @NotNull @Positive @Digits(integer = 15, fraction = 4)
        BigDecimal amount
) {

    // "from" is null: the funding account is chosen by the server, not by the client
    public String fingerprint() {
        return RequestFingerprint.of(TransactionType.DEPOSIT, null, accountId, amount);
    }
}
