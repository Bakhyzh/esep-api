package com.esep.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("esep.outbox")
public record OutboxProperties(
        @NotBlank String topic,
        // fixedDelay 0 would poll the database in a busy loop
        @NotNull @DurationMin(millis = 1) Duration pollInterval,
        @Min(1) int batchSize,
        // future.get(0) times out immediately: no event would ever be marked as published
        @NotNull @DurationMin(millis = 1) Duration sendTimeout,
        boolean publisherEnabled
) {
    public String deadLetterTopic() {
        return topic + ".DLT";
    }
}
