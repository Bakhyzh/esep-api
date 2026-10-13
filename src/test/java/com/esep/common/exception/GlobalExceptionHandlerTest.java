package com.esep.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;

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

    private static DataIntegrityViolationException integrity(String sqlState) {
        return new DataIntegrityViolationException("db error", new SQLException("ERROR: something", sqlState));
    }
}
