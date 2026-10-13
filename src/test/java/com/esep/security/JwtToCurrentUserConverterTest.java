package com.esep.security;

import com.esep.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtToCurrentUserConverterTest {

    private final JwtToCurrentUserConverter converter = new JwtToCurrentUserConverter();

    @Test
    void validToken_becomesCurrentUserWithRoleAuthority() {
        CurrentUserAuthentication authentication =
                (CurrentUserAuthentication) converter.convert(jwt("42", "ADMIN"));

        assertThat(authentication.getPrincipal()).isEqualTo(new CurrentUser(42L, Role.ADMIN));
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void nonNumericSubject_isAnInvalidTokenNotAServerError() {
        assertThatThrownBy(() -> converter.convert(jwt("alice@esep.dev", "USER")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void missingRole_isAnInvalidToken() {
        assertThatThrownBy(() -> converter.convert(jwt("42", null)))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void unknownRole_isAnInvalidToken() {
        assertThatThrownBy(() -> converter.convert(jwt("42", "SUPERUSER")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    static Jwt jwt(String subject, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS256").subject(subject);
        if (role != null) {
            builder.claim(JwtToCurrentUserConverter.ROLE_CLAIM, role);
        }
        return builder.build();
    }
}
