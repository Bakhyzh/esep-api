package com.esep.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.*;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewaySecurity {
    @Bean ReactiveJwtDecoder decoder(GatewayProperties properties) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            if (jwt.getExpiresAt()==null || jwt.getSubject()==null || jwt.getSubject().isBlank()
                    || !jwt.getAudience().contains("esep-api") || !"Bearer".equals(jwt.getClaimAsString("typ"))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()), claims));
        return decoder;
    }
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http, GatewayProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsOrigins());
        cors.setAllowedMethods(List.of("GET","HEAD","POST","PATCH","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key","X-Correlation-ID"));
        cors.setExposedHeaders(List.of("Location","Idempotent-Replayed","Retry-After","X-Correlation-ID"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",cors);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(c->c.configurationSource(source))
                .authorizeExchange(a->a.pathMatchers("/actuator/health","/actuator/health/**").permitAll()
                        .pathMatchers("/api/**").hasAnyRole("USER","ADMIN").anyExchange().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(jwt->
                        Mono.just(new JwtAuthenticationToken(jwt, authorities(jwt)))))).build();
    }
    static List<SimpleGrantedAuthority> authorities(Jwt jwt) {
        Object access=jwt.getClaims().get("resource_access");
        if (!(access instanceof Map<?,?> clients) || !(clients.get("esep-api") instanceof Map<?,?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(r->"USER".equals(r)||"ADMIN".equals(r)).distinct()
                .map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList();
    }
}
