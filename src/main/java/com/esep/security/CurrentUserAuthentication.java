package com.esep.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

/** Authentication whose principal is our CurrentUser, so controllers can use @AuthenticationPrincipal CurrentUser. */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser user;
    private final Jwt jwt;

    public CurrentUserAuthentication(CurrentUser user, Jwt jwt) {
        // "ROLE_" prefix: hasRole("ADMIN") checks for the authority "ROLE_ADMIN"
        this(user, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
    }

    public CurrentUserAuthentication(CurrentUser user, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.user = user;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }
}
