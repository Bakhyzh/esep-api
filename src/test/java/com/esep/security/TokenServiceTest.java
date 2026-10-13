package com.esep.security;

import com.esep.user.Role;
import com.esep.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "test-only-secret-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final JwtProperties properties = new JwtProperties(
            "test-only-secret-0123456789abcdef0123456789", "esep-api", Duration.ofMinutes(15));
    private final TokenService tokenService = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), properties);

    @Test
    void tokenCarriesIdRoleIssuerAndExpiry() {
        User user = new User("alice@esep.dev", "hash", Role.ADMIN);
        ReflectionTestUtils.setField(user, "id", 42L);

        TokenService.IssuedToken issued = tokenService.issue(user);
        Jwt jwt = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build().decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo("42");   // the id, never the email
        assertThat(jwt.getClaimAsString(JwtToCurrentUserConverter.ROLE_CLAIM)).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("esep-api");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "password");
    }
}
