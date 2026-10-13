package com.esep.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void newHashes_carryTheBcryptPrefix() {
        String hash = encoder.encode("password123");

        assertThat(hash).startsWith("{bcrypt}$2");
        assertThat(encoder.matches("password123", hash)).isTrue();
        assertThat(encoder.matches("password124", hash)).isFalse();
    }

    @Test
    void placeholderHashOfTheSystemUser_neverMatchesAndDoesNotThrow() {
        // V3 stores '!' for system@esep.internal: without the custom default the encoder would throw
        // IllegalArgumentException ("no PasswordEncoder mapped for the id null") and login would be a 500
        assertThat(encoder.matches("!", "!")).isFalse();
        assertThat(encoder.matches("", "!")).isFalse();
    }

    @Test
    void dummyHashUsedForUnknownEmails_isAValidBcryptHash() {
        // AuthService compares against it so that "unknown email" costs as much as "wrong password"
        String dummy = encoder.encode("dummy-password-for-timing");

        assertThat(encoder.matches("anything", dummy)).isFalse();
    }
}
