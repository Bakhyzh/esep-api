package com.esep.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("esep.security.keycloak")
public record KeycloakProperties(@NotBlank String issuerUri, @NotBlank String jwkSetUri) {
}
