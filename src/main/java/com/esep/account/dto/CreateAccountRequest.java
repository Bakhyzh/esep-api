package com.esep.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

// userId is temporary: in stage 5 the owner will be taken from the JWT, not from the body
public record CreateAccountRequest(
        @NotNull @Positive
        Long userId,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. KZT, USD")
        String currency
) {
}
