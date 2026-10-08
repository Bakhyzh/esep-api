package com.esep.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// no userId: the owner is always the authenticated user from the JWT, never from the body
public record CreateAccountRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. KZT, USD")
        String currency
) {
}
