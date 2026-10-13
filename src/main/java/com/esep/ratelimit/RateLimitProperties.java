package com.esep.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** At most {@code maxRequests} requests per client IP per endpoint in each {@code window}. */
@Validated
@ConfigurationProperties("esep.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) int maxRequests,
        // PEXPIRE with 0 deletes the counter at once: every request would be "the first" and nothing is limited
        @NotNull @DurationMin(millis = 1) Duration window
) {
}
