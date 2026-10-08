package com.esep.security;

import com.esep.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                // id, not email: the id never changes, an email can
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim(JwtToCurrentUserConverter.ROLE_CLAIM, user.getRole().name())
                .build();
        // a JWT is signed, NOT encrypted: anyone can base64-decode it, so no secrets in claims
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.ttl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
