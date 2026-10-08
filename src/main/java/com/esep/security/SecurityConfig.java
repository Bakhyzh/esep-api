package com.esep.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtToCurrentUserConverter jwtConverter,
                                            SecurityProblemHandler problemHandler) throws Exception {
        http
                // CSRF attacks abuse cookies the browser sends automatically; we use no cookies,
                // the token goes in the Authorization header explicitly, so CSRF protection is not needed
                .csrf(AbstractHttpConfigurer::disable)
                // no HttpSession: every request is authenticated by its own token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/deposits").hasRole("ADMIN")
                        // deny by default: a new endpoint is closed until someone opens it on purpose
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // default validators check exp/nbf (with 60s clock skew); we also require our issuer
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Stores hashes as "{bcrypt}$2a$10$...": the prefix lets us switch to a stronger algorithm later
     * and still verify old passwords.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        DelegatingPasswordEncoder encoder =
                (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        // a hash without a known {id} (e.g. '!' of the system user) must simply never match, not throw
        encoder.setDefaultPasswordEncoderForMatches(new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        });
        return encoder;
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
