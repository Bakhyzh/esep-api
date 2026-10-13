package com.esep.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** An origin that can never match must stop the app, not silently break every browser request. */
class CorsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void originsWithSchemeHostAndPort_start() {
        runner.withPropertyValues("esep.cors.allowed-origins=https://bakhyzh.github.io,http://localhost:5173")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void originWithAPath_failsOnStartup() {
        runner.withPropertyValues("esep.cors.allowed-origins=https://bakhyzh.github.io/esep-web")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("without a path"));
    }

    @Test
    void originWithATrailingSlash_failsOnStartup() {
        runner.withPropertyValues("esep.cors.allowed-origins=https://bakhyzh.github.io/")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wildcard_failsOnStartup() {
        runner.withPropertyValues("esep.cors.allowed-origins=*")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(CorsProperties.class)
    static class Config {
    }
}
