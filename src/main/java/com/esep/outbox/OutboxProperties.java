package com.esep.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("esep.outbox")
public record OutboxProperties(
        @NotBlank String topic,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotNull Duration sendTimeout,
        boolean publisherEnabled
) {
    public String deadLetterTopic() {
        return topic + ".DLT";
    }
}
