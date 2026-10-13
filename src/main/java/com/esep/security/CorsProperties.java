package com.esep.security;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Exact browser origins allowed to call the API (scheme + host + port), never "*" with credentials.
 * A browser sends Origin without a path, so "https://user.github.io/esep-web" or a trailing slash
 * would never match anything: every request from the site would fail CORS. Rejected on startup.
 */
@Validated
@ConfigurationProperties("esep.cors")
public record CorsProperties(
        @NotEmpty
        List<@Pattern(regexp = "^https?://[^/\\s*]+$",
                message = "must be scheme://host[:port], without a path or a trailing slash") String> allowedOrigins,
        Duration maxAge) {
}
