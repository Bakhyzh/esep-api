package com.esep.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakProfileGuardTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(KeycloakSecurityConfig.class)
            .withPropertyValues("esep.security.keycloak.issuer-uri=http://localhost:8180/realms/esep",
                    "esep.security.keycloak.jwk-set-uri=http://localhost:8180/realms/esep/protocol/openid-connect/certs");

    @Test
    void productionCannotEnableIntegrationEvenTogetherWithDev() {
        runner.withPropertyValues("spring.profiles.active=prod,dev,keycloak-integration")
                .run(c -> assertThat(c).hasFailed().getFailure().rootCause().hasMessageContaining("forbids prod"));
    }

    @Test
    void integrationAloneIsNotAnAllowedEnvironment() {
        runner.withPropertyValues("spring.profiles.active=keycloak-integration")
                .run(c -> assertThat(c).hasFailed().getFailure().rootCause().hasMessageContaining("requires dev or test"));
    }

    @Test
    void demoProvisioningCannotRunInIntegration() {
        runner.withPropertyValues("spring.profiles.active=dev,keycloak-integration", "esep.demo.enabled=true")
                .run(c -> assertThat(c).hasFailed().getFailure().rootCause().hasMessageContaining("Demo provisioning"));
    }

    @Test
    void validatedSettingsAndExplicitDevPermitOnlyOneDecoder() {
        runner.withPropertyValues("spring.profiles.active=dev,keycloak-integration")
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(org.springframework.security.oauth2.jwt.JwtDecoder.class));
    }

    @Test
    void missingSettingsFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(KeycloakSecurityConfig.class)
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=test,keycloak-integration")
                .run(c -> assertThat(c).hasFailed());
    }
}
