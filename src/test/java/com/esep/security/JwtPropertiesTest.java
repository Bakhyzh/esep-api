package com.esep.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Fail fast: the app must refuse to start with a missing or weak JWT secret. */
class JwtPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("esep.security.jwt.issuer=esep-api", "esep.security.jwt.ttl=1h");

    @Test
    void missingSecret_contextFails() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shortSecret_contextFails() {
        runner.withPropertyValues("esep.security.jwt.secret=too-short")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("256 bits"));
    }

    @Test
    void validSecret_contextStarts() {
        runner.withPropertyValues("esep.security.jwt.secret=0123456789abcdef0123456789abcdef")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtProperties.class));
    }

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {
    }
}
