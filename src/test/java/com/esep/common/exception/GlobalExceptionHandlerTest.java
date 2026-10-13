package com.esep.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import com.esep.ratelimit.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

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

    private static DataIntegrityViolationException integrity(String sqlState) {
        return new DataIntegrityViolationException("db error", new SQLException("ERROR: something", sqlState));
    }
}
