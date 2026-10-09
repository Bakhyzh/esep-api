package com.esep.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Map;

/**
 * Limits brute force on login and mass registration: per client IP, separately for each endpoint.
 * Runs after the Spring Security chain (a plain servlet filter), so a 429 still carries CORS headers
 * and the frontend can read it. The error is rendered by GlobalExceptionHandler, like every other error.
 * <p>
 * The client IP is request.getRemoteAddr(): behind Caddy it is the real client thanks to
 * server.forward-headers-strategy (prod profile), which trusts X-Forwarded-For only from internal proxies.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, String> LIMITED = Map.of(
            "/api/auth/login", "login",
            "/api/auth/register", "register");

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;
    private final HandlerExceptionResolver resolver;

    public AuthRateLimitFilter(RedisRateLimiter limiter, RateLimitProperties properties,
                               @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.limiter = limiter;
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled()
                || !"POST".equals(request.getMethod())
                || !LIMITED.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = "esep:rate-limit:" + LIMITED.get(request.getRequestURI()) + ":" + request.getRemoteAddr();
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryAcquire(key, properties.maxRequests(), properties.window());
        } catch (DataAccessException e) {
            // fail open: Redis down must not lock every user out of login; the password check still applies
            log.warn("Rate limiter unavailable, request allowed: {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        }
        if (!decision.allowed()) {
            log.info("Rate limit exceeded: {} from {}", request.getRequestURI(), request.getRemoteAddr());
            resolver.resolveException(request, response, null, new RateLimitExceededException(decision.retryAfter()));
            return;
        }
        chain.doFilter(request, response);
    }
}
