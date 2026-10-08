package com.esep.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Validated on startup: a missing or short secret stops the app instead of issuing weak tokens. */
@Validated
@ConfigurationProperties("esep.security.jwt")
public record JwtProperties(
        // HS256 needs a key of at least 256 bits = 32 bytes
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256")
        String secret,

        @NotBlank
        String issuer,

        @NotNull
        Duration ttl
) {
}
