package com.esep.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single place that turns every exception into the same JSON shape (RFC 9457 problem+json):
 * <pre>
 * { "type": "about:blank", "title": "...", "status": 422, "detail": "human-readable message",
 *   "instance": "/api/transfers", "code": "INSUFFICIENT_FUNDS", "timestamp": "...",
 *   "errors": [ { "field": "amount", "message": "must be greater than 0" } ]   // validation only
 * }
 * </pre>
 * Standard Spring MVC errors (unknown route, wrong method, bad JSON, missing header) come through
 * {@link #handleExceptionInternal} and get a code too; security filter errors are routed here
 * by SecurityProblemHandler.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated", ex.getMessage(), ex.getCode());
    }

    // 401: who are you? (no token, bad/expired token, wrong password on login)
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        // never echo token parsing details back; the login message is safe and written by us
        if (ex instanceof BadCredentialsException) {
            return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), ErrorCode.INVALID_CREDENTIALS);
        }
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Missing, invalid or expired access token",
                ErrorCode.UNAUTHORIZED);
    }

    // 403: I know who you are, but you are not allowed to do this
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this action",
                ErrorCode.FORBIDDEN);
    }

    // parent of both optimistic (@Version) and pessimistic (deadlock, lock timeout) failures:
    // the request itself is fine, it just lost a race, so the client may safely retry
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The resource was modified by another request, please retry", ErrorCode.CONCURRENT_MODIFICATION);
    }

    // last line of defense: a DB constraint caught what the service did not (unique key, check, FK)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Data conflict", "Request conflicts with existing data", ErrorCode.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // log the details, but never leak stack traces / SQL to the client
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error occurred",
                ErrorCode.INTERNAL_ERROR);
    }

    // @Valid @RequestBody failed and the method has no other constrained parameters
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.add(new FieldError(error.getField(), error.getDefaultMessage())));
        return validationFailed(errors);
    }

    // Spring 6.1+ method validation: thrown instead of the above when constraints sit directly
    // on method parameters (e.g. @NotBlank on a @RequestHeader), and then covers @Valid bodies too
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors.getFieldErrors()
                        .forEach(error -> errors.add(new FieldError(error.getField(), error.getDefaultMessage())));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors()
                        .forEach(error -> errors.add(new FieldError(name, error.getDefaultMessage())));
            }
        }
        return validationFailed(errors);
    }

    /** All other standard Spring MVC exceptions: keep Spring's status and detail, add our code and timestamp. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            enrich(problem, codeFor(ex, statusCode));
        }
        return response;
    }

    private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof HttpMessageNotReadableException) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        if (ex instanceof ServletRequestBindingException) {
            return ErrorCode.MISSING_PARAMETER;   // missing query parameter or header
        }
        if (ex instanceof TypeMismatchException) {
            return ErrorCode.INVALID_REQUEST;     // e.g. ?zone=Mars/Olympus or ?page=abc
        }
        if (ex instanceof NoResourceFoundException) {
            return ErrorCode.RESOURCE_NOT_FOUND;  // unknown URL
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
    }

    private static ResponseEntity<Object> validationFailed(List<FieldError> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request has invalid fields",
                ErrorCode.VALIDATION_FAILED);
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        return parameter.getParameterName();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        enrich(problem, code);
        return problem;
    }

    private static void enrich(ProblemDetail problem, ErrorCode code) {
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now());
    }

    /** One invalid field; a field may appear several times if it breaks several rules. */
    public record FieldError(String field, String message) {
    }
}
