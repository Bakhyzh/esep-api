package com.esep.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("esep.rate-limit.enabled=true", "esep.rate-limit.max-requests=10");

    @Test
    void positiveWindow_starts() {
        runner.withPropertyValues("esep.rate-limit.window=1m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void zeroWindow_wouldDisableTheLimit_soTheContextFails() {
        runner.withPropertyValues("esep.rate-limit.window=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void zeroMaxRequests_contextFails() {
        runner.withPropertyValues("esep.rate-limit.window=1m", "esep.rate-limit.max-requests=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {
    }
}
