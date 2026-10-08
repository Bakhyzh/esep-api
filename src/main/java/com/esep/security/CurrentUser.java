package com.esep.security;

import com.esep.user.Role;

/** Who is calling, taken from a verified JWT. No database lookup per request. */
public record CurrentUser(Long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean canAccess(Long ownerId) {
        return isAdmin() || id.equals(ownerId);
    }
}
