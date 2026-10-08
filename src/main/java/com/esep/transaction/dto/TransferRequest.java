package com.esep.transaction.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull @Positive
        Long fromAccountId,

        @NotNull @Positive
        Long toAccountId,

        // NUMERIC(19,4): at most 15 digits before the point and 4 after
        @NotNull @Positive @Digits(integer = 15, fraction = 4)
        BigDecimal amount
) {
}
