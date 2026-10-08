package com.esep.auth;

import com.esep.auth.dto.LoginRequest;
import com.esep.auth.dto.RegisterRequest;
import com.esep.auth.dto.TokenResponse;
import com.esep.auth.dto.UserResponse;
import com.esep.common.exception.ConflictException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.TokenService;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    // compared against when the email is unknown, so "no such user" takes as long as "wrong password"
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }
        try {
            User user = userRepository.saveAndFlush(
                    new User(email, passwordEncoder.encode(request.password()), Role.USER));
            return UserResponse.from(user);
        } catch (DataIntegrityViolationException e) {
            // two parallel registrations with the same email: the UNIQUE constraint stopped the second
            throw new ConflictException("Email is already registered");
        }
    }

    public TokenResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(normalize(request.email()));
        boolean passwordMatches = passwordEncoder.matches(
                request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !passwordMatches) {
            // one message for both cases: do not tell an attacker which emails exist
            throw new BadCredentialsException("Invalid email or password");
        }
        TokenService.IssuedToken token = tokenService.issue(user.get());
        return TokenResponse.bearer(token.value(), token.expiresInSeconds());
    }

    public UserResponse me(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
