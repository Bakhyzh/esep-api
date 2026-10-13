package com.esep.demo;

import com.esep.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
        // the hash is BCrypt: over 72 bytes the encoder throws and the app dies with an obscure error
        @NotBlank @Size(min = 8, message = "must be at least 8 characters") @MaxUtf8Bytes(72) String password,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 code, e.g. KZT") String currency,
        // same limits as a deposit request: a value it would reject must not reach the seeder
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal initialBalance
) {
}
