package com.esep.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.util.*;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,SecurityProblemHandler errors) throws Exception {
        return http.cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a
                    .requestMatchers("/actuator/prometheus", "/actuator/health","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**","/error").permitAll()
                    .requestMatchers("/internal/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST,"/api/users/*/disable","/api/users/*/enable").hasRole("ADMIN")
                    .requestMatchers("/api/auth/me","/api/users/me").hasAnyRole("USER","ADMIN")
                    .anyRequest().denyAll())
                .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(jwt->new JwtAuthenticationToken(jwt,roles(jwt))))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .exceptionHandling(e->e.authenticationEntryPoint(errors).accessDeniedHandler(errors)).build();
    }
    public static List<SimpleGrantedAuthority> roles(Jwt jwt) {
        Object claim=jwt.getClaims().get("resource_access");
        if(!(claim instanceof Map<?,?> access) || !(access.get("esep-api") instanceof Map<?,?> api)
            || !(api.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(x->x instanceof String && Set.of("USER","ADMIN").contains(x))
                .map(x->new SimpleGrantedAuthority("ROLE_"+x)).distinct().toList();
    }
    @Bean CorsConfigurationSource cors(CorsProperties properties) {
        var c=new CorsConfiguration();c.setAllowedOrigins(properties.allowedOrigins());
        c.setAllowedMethods(List.of("GET","PATCH","POST","OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization","Content-Type","X-Correlation-ID"));c.setAllowCredentials(false);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
    }
}
