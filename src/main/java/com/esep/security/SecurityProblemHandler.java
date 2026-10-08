package com.esep.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security filters run BEFORE Spring MVC, so @RestControllerAdvice does not see their exceptions.
 * We hand them over to the MVC exception resolver, and GlobalExceptionHandler renders
 * 401/403 in the same problem+json format as every other error.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public SecurityProblemHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) {
        resolver.resolveException(request, response, null, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) {
        resolver.resolveException(request, response, null, ex);
    }
}
