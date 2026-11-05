package com.esep.security;

import com.esep.user.IdentityResolutionService;
import com.esep.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Called only AFTER RS256/JWKS and issuer/audience/time validation. */
@Component
@Profile("keycloak-integration")
@RequiredArgsConstructor
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final IdentityResolutionService identities;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = clientRoles(jwt);
        // A valid token does not imply a business role or a verified local identity.
        // An empty-authorities principal is rejected by URL authorization with 403,
        // before controllers can receive a CurrentUser. Never fabricate a USER role.
        if (roles.isEmpty()) {
            return new JwtAuthenticationToken(jwt, List.of());
        }
        var userId = identities.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        if (userId.isEmpty()) {
            return new JwtAuthenticationToken(jwt, List.of());
        }
        Role effective = roles.contains("ADMIN") ? Role.ADMIN : Role.USER;
        var authorities = roles.stream().sorted().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return new CurrentUserAuthentication(new CurrentUser(userId.get(), effective), jwt, authorities);
    }

    private static Set<String> clientRoles(Jwt jwt) {
        Object access = jwt.getClaims().get("resource_access");
        if (!(access instanceof Map<?, ?> resources)
                || !(resources.get("esep-api") instanceof Map<?, ?> api)
                || !(api.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(Set.of("USER", "ADMIN")::contains).collect(Collectors.toUnmodifiableSet());
    }
}
