package com.esep.auth.dto;

import com.esep.user.Role;
import com.esep.user.User;

import java.time.Instant;

// never expose passwordHash: that is exactly why entities are not returned from controllers
public record UserResponse(Long id, String email, Role role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
