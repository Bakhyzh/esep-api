package com.esep.demo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/** Bound and validated only when esep.demo.enabled=true (the seeder is conditional). */
@Validated
@ConfigurationProperties("esep.demo")
public record DemoProperties(
        boolean enabled,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password,
        @NotBlank String currency,
        @NotNull @PositiveOrZero BigDecimal initialBalance
) {
}
