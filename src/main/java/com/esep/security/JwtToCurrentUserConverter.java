package com.esep.security;

import com.esep.user.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/** Runs after the signature, exp and iss of the token are already verified by JwtDecoder. */
@Component
public class JwtToCurrentUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String ROLE_CLAIM = "role";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentUser user = new CurrentUser(
                userId(jwt),
                role(jwt));
        return new CurrentUserAuthentication(user, jwt);
    }

    // an AuthenticationException becomes a 401; any other exception here escapes the security filter
    private static Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Token subject is not a user id");
        }
    }

    // missing claim -> Role.valueOf(null) is an NPE; unknown name (a role removed later) -> IllegalArgumentException
    private static Role role(Jwt jwt) {
        String role = jwt.getClaimAsString(ROLE_CLAIM);
        try {
            return Role.valueOf(role);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Token has no valid role");
        }
    }
}
