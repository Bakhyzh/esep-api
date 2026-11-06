package com.esep.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("esep.outbox.topic=esep.transfers", "esep.outbox.poll-interval=1s",
                    "esep.outbox.batch-size=100", "esep.outbox.send-timeout=5s");

    @Test
    void defaults_start() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void zeroPollInterval_contextFails() {
        runner.withPropertyValues("esep.outbox.poll-interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroSendTimeout_contextFails() {
        runner.withPropertyValues("esep.outbox.send-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(OutboxProperties.class)
    static class Config {
    }
}
