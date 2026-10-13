package com.esep.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import com.esep.ratelimit.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void uniqueViolation_isConflict() {
        ProblemDetail problem = handler.handleDataIntegrity(integrity("23505"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT");
    }

    @Test
    void dataException_isBadRequestNotConflict() {
        // 22021: invalid byte sequence (a NUL character), 22003: numeric value out of range
        for (String state : new String[]{"22021", "22003", "22008"}) {
            ProblemDetail problem = handler.handleDataIntegrity(integrity(state));

            assertThat(problem.getStatus()).as(state).isEqualTo(400);
            assertThat(problem.getProperties()).as(state).containsEntry("code", "INVALID_REQUEST");
        }
    }

    @Test
    void integrityViolationWithoutSqlState_staysConflict() {
        ProblemDetail problem = handler.handleDataIntegrity(new DataIntegrityViolationException("no cause"));

        assertThat(problem.getStatus()).isEqualTo(409);
    }

    @Test
    void rateLimit_retryAfterIsRoundedUpToWholeSeconds() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimit(new RateLimitExceededException(Duration.ofMillis(1500)));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody().getProperties()).containsEntry("code", "RATE_LIMITED");
    }

    @Test
    void rateLimit_retryAfterIsNeverZero() {
        // "Retry-After: 0" would invite an immediate retry that is still inside the window
        ResponseEntity<ProblemDetail> response = handler.handleRateLimit(new RateLimitExceededException(Duration.ZERO));

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void lostLockRace_isRetryableConflict() {
        ProblemDetail problem = handler.handleConcurrencyFailure(
                new PessimisticLockingFailureException("deadlock detected"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "CONCURRENT_MODIFICATION");
        assertThat(problem.getDetail()).contains("retry");
    }

    @Test
    void unexpectedError_doesNotLeakItsMessage() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("password=secret at jdbc:postgresql://db:5432/esep"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Unexpected error occurred");
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void wrongPassword_andBadToken_haveDifferentCodes() {
        ProblemDetail login = handler.handleAuthentication(new BadCredentialsException("Invalid email or password"));
        ProblemDetail token = handler.handleAuthentication(new InvalidBearerTokenException("Jwt expired at ..."));

        assertThat(login.getProperties()).containsEntry("code", "INVALID_CREDENTIALS");
        assertThat(token.getProperties()).containsEntry("code", "UNAUTHORIZED");
        // token parsing details stay on the server
        assertThat(token.getDetail()).isEqualTo("Missing, invalid or expired access token");
    }

    private static DataIntegrityViolationException integrity(String sqlState) {
        return new DataIntegrityViolationException("db error", new SQLException("ERROR: something", sqlState));
    }
}
