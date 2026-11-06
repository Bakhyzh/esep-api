package com.esep.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Opt-in migration environment, never a second/fallback decoder. */
@Configuration(proxyBeanMethods = false)
@Profile("keycloak-integration")
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakSecurityConfig {
    @Bean
    JwtDecoder keycloakJwtDecoder(KeycloakProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.acceptsProfiles(Profiles.of("dev", "test", "local-keycloak"))) {
            throw new IllegalStateException("keycloak-integration requires dev or test or local-keycloak and forbids prod");
        }
        if (environment.getProperty("esep.demo.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Demo provisioning must be disabled for keycloak-integration");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                    || !jwt.getAudience().contains("esep-api")
                    || !"Bearer".equals(jwt.getClaimAsString("typ"))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Required access-token claims are invalid", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()), claims));
        return decoder;
    }
}
