package com.esep.auth;

import com.esep.auth.dto.LoginRequest;
import com.esep.auth.dto.RegisterRequest;
import com.esep.auth.dto.TokenResponse;
import com.esep.auth.dto.UserResponse;
import com.esep.common.exception.ConflictException;
import com.esep.security.TokenService;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // a real encoder: we want to test that hashing actually happens, not mock it away
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenService);
    }

    @Test
    void register_normalizesEmailHashesPasswordAndAlwaysAssignsUserRole() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0), 7L));

        UserResponse response = authService.register(new RegisterRequest("  Alice@Example.COM ", "secret-pass"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash()).startsWith("{bcrypt}").doesNotContain("secret-pass");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    void register_existingEmail_throwsConflict() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "secret-pass")))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_parallelDuplicateCaughtByUniqueConstraint_throwsConflict() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "secret-pass")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void login_correctPassword_issuesToken() {
        User user = withId(new User("alice@example.com", passwordEncoder.encode("secret-pass"), Role.USER), 7L);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issue(user)).thenReturn(new TokenService.IssuedToken("jwt-token", 3600));

        TokenResponse token = authService.login(new LoginRequest("ALICE@example.com", "secret-pass"));

        assertThat(token.accessToken()).isEqualTo("jwt-token");
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isEqualTo(3600);
    }

    @Test
    void login_wrongPasswordAndUnknownEmail_failWithTheSameMessage() {
        User user = new User("alice@example.com", passwordEncoder.encode("secret-pass"), Role.USER);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "wrong-pass")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "secret-pass")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(tokenService, never()).issue(any());
    }

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
