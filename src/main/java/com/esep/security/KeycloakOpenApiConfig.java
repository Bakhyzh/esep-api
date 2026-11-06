package com.esep.security;

import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile("keycloak-integration")
public class KeycloakOpenApiConfig {
    @Bean
    OpenApiCustomizer keycloakApiDocumentation(KeycloakProperties properties) {
        return api -> {
            api.getPaths().remove("/api/auth/login");
            api.getPaths().remove("/api/auth/register");
            api.getComponents().addSecuritySchemes("keycloak", new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2).flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                            .authorizationUrl(properties.issuerUri() + "/protocol/openid-connect/auth")
                            .tokenUrl(properties.issuerUri() + "/protocol/openid-connect/token")
                            .scopes(new Scopes().addString("openid", "OpenID Connect")))));
            api.setSecurity(List.of(new SecurityRequirement().addList("keycloak", "openid")));
            api.getInfo().setDescription(api.getInfo().getDescription()
                    + " Keycloak Code + PKCE S256; Swagger redirect URI must be explicitly registered before use.");
        };
    }
}
