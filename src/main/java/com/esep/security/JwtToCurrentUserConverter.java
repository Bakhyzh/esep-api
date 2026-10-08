package com.esep.security;

import com.esep.user.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Runs after the signature, exp and iss of the token are already verified by JwtDecoder. */
@Component
public class JwtToCurrentUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String ROLE_CLAIM = "role";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentUser user = new CurrentUser(
                Long.valueOf(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM)));
        return new CurrentUserAuthentication(user, jwt);
    }
}
