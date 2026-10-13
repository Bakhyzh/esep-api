package com.esep.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** A bad demo configuration must stop the app with a readable message, not halfway through seeding. */
class DemoPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("esep.demo.enabled=true", "esep.demo.email=demo@esep.dev",
                    "esep.demo.password=demo-password", "esep.demo.currency=KZT", "esep.demo.initial-balance=5000");

    @Test
    void validConfiguration_starts() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void passwordOver72Bytes_failsOnStartup() {
        runner.withPropertyValues("esep.demo.password=" + "пароль".repeat(7))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("72 bytes"));
    }

    @Test
    void initialBalanceWithMoreThanFourDecimals_failsOnStartup() {
        runner.withPropertyValues("esep.demo.initial-balance=100.00001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void lowercaseCurrency_failsOnStartup() {
        runner.withPropertyValues("esep.demo.currency=kzt")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("ISO 4217"));
    }

    @Configuration
    @EnableConfigurationProperties(DemoProperties.class)
    static class Config {
    }
}
