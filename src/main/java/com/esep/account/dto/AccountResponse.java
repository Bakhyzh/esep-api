package com.esep.account.dto;

import com.esep.account.Account;
import com.esep.account.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        Long userId,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt,
        Instant closedAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUser().getId(),
                account.getCurrency().getCurrencyCode(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getClosedAt()
        );
    }
}
