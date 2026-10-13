package com.esep.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        // PostgreSQL text cannot hold U+0000: the lookup failed with a data exception, answered as 409 CONFLICT
        @NotBlank @Pattern(regexp = "[^\\u0000]*", message = "must not contain NUL characters")
        String email,

        @NotBlank String password
) {
}
