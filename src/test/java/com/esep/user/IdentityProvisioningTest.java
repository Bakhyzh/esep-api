package com.esep.user;

import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityProvisioningTest extends IntegrationTest {
    @Autowired IdentityProvisioningService provisioning;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentFirstLoginsCreateExactlyOneUserAndMapping() throws Exception {
        String sub = UUID.randomUUID().toString();
        String email = sub + "@test.esep";
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = IntStream.range(0, 16).mapToObj(i ->
                    (Callable<Long>) () -> provisioning.provision("https://issuer.test", sub, email).orElseThrow()).toList();
            var results = pool.invokeAll(calls);
            Long id = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(id);
            var user = users.findById(id).orElseThrow();
            assertThat(user.getPasswordHash()).isNull();
            assertThat(user.getRole()).isEqualTo(Role.USER);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id=?", Long.class, id)).isEqualTo(1);
        }
    }

    @Test
    void emailCollisionNeverLinksExistingUserOrSystemIdentity() {
        String email = UUID.randomUUID() + "@test.esep";
        User original = users.saveAndFlush(new User(email, "preserved", Role.ADMIN));
        assertThat(provisioning.provision("https://issuer.test", UUID.randomUUID().toString(), email)).isEmpty();
        assertThat(users.findById(original.getId()).orElseThrow().getPasswordHash()).isEqualTo("preserved");
        assertThat(provisioning.provision("https://issuer.test", "system", "system@esep.internal")).isEmpty();
        assertThat(provisioning.provision("https://issuer.test", "blank", null)).isEmpty();
    }

    @Test
    void differentSubjectsWithSameEmailCannotAcquireSameUser() throws Exception {
        String email = UUID.randomUUID() + "@test.esep";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(java.util.List.of(
                    (Callable<java.util.Optional<Long>>) () -> provisioning.provision("https://issuer.test", UUID.randomUUID().toString(), email),
                    () -> provisioning.provision("https://issuer.test", UUID.randomUUID().toString(), email)));
            assertThat(results.stream().filter(f -> { try { return f.get().isPresent(); } catch (Exception e) { throw new RuntimeException(e); } }).count()).isEqualTo(1);
        }
    }
}
