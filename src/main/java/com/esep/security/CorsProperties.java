package com.esep.security;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Exact browser origins allowed to call the API (scheme + host + port), never "*" with credentials. */
@Validated
@ConfigurationProperties("esep.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins, Duration maxAge) {
}
