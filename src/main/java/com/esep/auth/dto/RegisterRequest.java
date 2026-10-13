package com.esep.auth.dto;

import com.esep.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// no "role" field on purpose: a client must never choose its own role (mass assignment)
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255)
        String email,

        // BCrypt accepts at most 72 BYTES: 72 ASCII letters, but only 36 Cyrillic ones
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72)
        String password
) {
}
