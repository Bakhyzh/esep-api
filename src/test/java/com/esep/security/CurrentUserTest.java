package com.esep.security;

import com.esep.user.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    @Test
    void userAccessesOnlyOwnResources() {
        CurrentUser user = new CurrentUser(1L, Role.USER);

        assertThat(user.canAccess(1L)).isTrue();
        assertThat(user.canAccess(2L)).isFalse();
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    void adminAccessesEverything() {
        CurrentUser admin = new CurrentUser(99L, Role.ADMIN);

        assertThat(admin.canAccess(1L)).isTrue();
        assertThat(admin.isAdmin()).isTrue();
    }

    @Test
    void idsAreComparedByValueNotByReference() {
        // Long outside the -128..127 cache: == would be false for equal ids
        assertThat(new CurrentUser(1000L, Role.USER).canAccess(Long.valueOf(1000L))).isTrue();
    }
}
