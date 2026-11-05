package com.esep.security;

import com.esep.user.Role;

/** Local business identity. OIDC resolves validated issuer/sub through an explicit DB mapping. */
public record CurrentUser(Long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean canAccess(Long ownerId) {
        return isAdmin() || id.equals(ownerId);
    }
}
