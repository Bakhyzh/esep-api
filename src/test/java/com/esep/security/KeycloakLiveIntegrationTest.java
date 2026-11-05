package com.esep.security;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.support.IntegrationTest;
import com.esep.user.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in real Keycloak token obtained by backend-smoke.py using Code + PKCE. */
@EnabledIfEnvironmentVariable(named = "ESEP_KEYCLOAK_LIVE", matches = "true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("keycloak-integration")
class KeycloakLiveIntegrationTest extends IntegrationTest {
    @Autowired JwtDecoder decoder;
    @Autowired UserRepository users;
    @Autowired UserIdentityRepository identities;
    @Autowired AccountRepository accounts;
    @Autowired MockMvc mvc;

    @Test
    void realKeycloakCodeFlowToken_accessesExistingLocalAccountOnlyAfterExplicitLink() throws Exception {
        String raw = System.getenv("ESEP_KEYCLOAK_ACCESS_TOKEN");
        var jwt = decoder.decode(raw); // Real Keycloak JWKS, no mocked decoder or jwt() helper.
        User existing = users.save(new User(UUID.randomUUID() + "@test.esep", "legacy-hash-preserved", Role.USER));
        Account existingAccount = accounts.save(new Account(existing, Currency.getInstance("KZT")));
        mvc.perform(get("/api/accounts/" + existingAccount.getId()).header("Authorization", "Bearer " + raw))
                .andExpect(status().isForbidden());
        identities.saveAndFlush(new UserIdentity(existing, jwt.getIssuer().toString(), jwt.getSubject(), "live-test-approved-link"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(existing.getId()));
        mvc.perform(get("/api/accounts/" + existingAccount.getId()).header("Authorization", "Bearer " + raw))
                .andExpect(status().isOk());
        User other = users.save(new User(UUID.randomUUID() + "@test.esep", "legacy-hash-preserved", Role.USER));
        Account otherAccount = accounts.save(new Account(other, Currency.getInstance("KZT")));
        mvc.perform(get("/api/accounts/" + otherAccount.getId()).header("Authorization", "Bearer " + raw))
                .andExpect(status().isNotFound());
        assertThat(users.findById(existing.getId()).orElseThrow().getPasswordHash()).isEqualTo("legacy-hash-preserved");
    }
}
