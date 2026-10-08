package com.esep.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// no "role" field on purpose: a client must never choose its own role (mass assignment)
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255)
        String email,

        // BCrypt uses only the first 72 bytes of a password
        @NotBlank @Size(min = 8, max = 72)
        String password
) {
}
