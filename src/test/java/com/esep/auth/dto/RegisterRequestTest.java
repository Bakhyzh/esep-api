package com.esep.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void asciiPasswordOf72Characters_isValid() {
        assertThat(violations("a".repeat(72))).isEmpty();
    }

    @Test
    void cyrillicPasswordOver72Bytes_isRejectedEvenThoughItHasFewerThan72Characters() {
        String password = "пароль".repeat(7);   // 42 characters, 84 bytes

        assertThat(violations(password))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("password");
    }

    @Test
    void cyrillicPasswordOf72Bytes_isValid() {
        assertThat(violations("пароль".repeat(6))).isEmpty();   // 36 characters, 72 bytes
    }

    @Test
    void shortPassword_isRejected() {
        assertThat(violations("short")).hasSize(1);
    }

    private static Set<ConstraintViolation<RegisterRequest>> violations(String password) {
        return validator.validate(new RegisterRequest("user@esep.dev", password));
    }
}
