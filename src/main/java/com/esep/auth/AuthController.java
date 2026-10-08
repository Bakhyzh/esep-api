package com.esep.auth;

import com.esep.auth.dto.LoginRequest;
import com.esep.auth.dto.RegisterRequest;
import com.esep.auth.dto.TokenResponse;
import com.esep.auth.dto.UserResponse;
import com.esep.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        return authService.me(currentUser.id());
    }
}
