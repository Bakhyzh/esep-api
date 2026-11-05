package com.esep.security;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.support.IntegrationTest;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.DepositRequest;
import com.esep.user.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP JWKS + RSA verification, full filter chain, real PostgreSQL/Kafka/Redis. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("keycloak-integration")
class KeycloakSecurityIntegrationTest extends IntegrationTest {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String ISSUER;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] json = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                try (var out = exchange.getResponseBody()) { out.write(json); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/realms/esep";
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("esep.security.keycloak.issuer-uri", () -> ISSUER);
        registry.add("esep.security.keycloak.jwk-set-uri", () ->
                "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopIssuer() { JWKS.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserIdentityRepository identities;
    @Autowired AccountRepository accounts;
    @Autowired TransactionService transactions;
    @Autowired JwtEncoder legacyEncoder;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void validOpaqueSubject_resolvesExistingId_andEffectiveRoleWithoutChangingUser() throws Exception {
        Linked user = linked(Role.USER);
        String jwt = token(user.subject(), List.of("USER", "ADMIN"), b -> {});
        mvc.perform(auth(get("/api/auth/me"), jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.user().getId()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.email").value(user.user().getEmail()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        assertThat(users.findById(user.user().getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1).containsKey("keycloakJwtDecoder");
    }

    @Test
    void cryptographicAndRequiredClaimFailures_return401() throws Exception {
        Linked user = linked(Role.USER);
        List<Consumer<JWTClaimsSet.Builder>> invalid = List.of(
                b -> b.issuer(ISSUER + "-wrong"), b -> b.audience("other-api"),
                b -> b.expirationTime(Date.from(Instant.now().minusSeconds(180))),
                b -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(180))),
                b -> b.expirationTime(null), b -> b.subject(" "), b -> b.claim("typ", "ID"));
        for (var mutate : invalid) {
            mvc.perform(auth(get("/api/accounts"), token(user.subject(), List.of("USER"), mutate)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        RSAKey attacker = new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate();
        mvc.perform(auth(get("/api/accounts"), sign(claims(user.subject(), List.of("ADMIN")), attacker)))
                .andExpect(status().isUnauthorized());
        var legacyClaims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(ISSUER).subject(user.subject()).audience(List.of("esep-api"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("role", "ADMIN").build();
        String hs256 = legacyEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), legacyClaims)).getTokenValue();
        mvc.perform(auth(get("/api/accounts"), hs256)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownSubject_doesNotLinkByEmailOrLocalIdClaim() throws Exception {
        User legacy = users.save(new User(UUID.randomUUID() + "@test.esep", "unchanged", Role.ADMIN));
        long mappings = identities.count();
        mvc.perform(auth(get("/api/auth/me"), token(UUID.randomUUID().toString(), List.of("ADMIN"), b ->
                        b.claim("email", legacy.getEmail()).claim("email_verified", true)
                                .claim("local_user_id", legacy.getId()))))
                .andExpect(status().isForbidden());
        assertThat(identities.count()).isEqualTo(mappings);
        assertThat(users.findById(legacy.getId()).orElseThrow().getPasswordHash()).isEqualTo("unchanged");
    }

    @Test
    void absentMalformedOrOtherClientRoles_neverGrantAccess() throws Exception {
        Linked user = linked(Role.ADMIN);
        List<Object> access = List.of(
                Map.of(), "not-an-object",
                Map.of("esep-api", Map.of("roles", "ADMIN")),
                Map.of("other-api", Map.of("roles", List.of("ADMIN"))),
                Map.of("esep-api", Map.of("roles", List.of("SUPERUSER"))));
        for (Object claim : access) {
            mvc.perform(auth(get("/api/accounts"), token(user.subject(), List.of(), b ->
                            b.claim("resource_access", claim).claim("realm_access", Map.of("roles", List.of("ADMIN"))))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void userCannotDepositOrReadAnotherAccount_adminStillCannotSpendAnotherUsersMoney() throws Exception {
        Linked alice = linked(Role.USER), bob = linked(Role.USER), admin = linked(Role.USER);
        Account a = accounts.save(new Account(alice.user(), Currency.getInstance("KZT")));
        Account b = accounts.save(new Account(bob.user(), Currency.getInstance("KZT")));
        String aliceJwt = token(alice.subject(), List.of("USER"), x -> {});
        String bobJwt = token(bob.subject(), List.of("USER"), x -> {});
        String adminJwt = token(admin.subject(), List.of("ADMIN"), x -> {});
        mvc.perform(auth(get("/api/accounts/" + a.getId()), aliceJwt)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/accounts/" + a.getId()), bobJwt)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/accounts").param("userId", alice.user().getId().toString()), bobJwt))
                .andExpect(status().isForbidden());
        String deposit = "{\"accountId\":" + a.getId() + ",\"amount\":100}";
        mvc.perform(auth(post("/api/deposits"), aliceJwt).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).content(deposit))
                .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/deposits"), adminJwt).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).content(deposit))
                .andExpect(status().isCreated());
        String transfer = "{\"fromAccountId\":" + a.getId() + ",\"toAccountId\":" + b.getId() + ",\"amount\":25}";
        for (String jwt : List.of(bobJwt, adminJwt)) {
            mvc.perform(auth(post("/api/transfers"), jwt).contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString()).content(transfer))
                    .andExpect(status().isNotFound());
        }
        String key = UUID.randomUUID().toString();
        mvc.perform(auth(post("/api/transfers"), aliceJwt).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(transfer)).andExpect(status().isCreated());
        mvc.perform(auth(post("/api/transfers"), aliceJwt).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(transfer)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, a.getId()))
                .isEqualByComparingTo("75");
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, b.getId()))
                .isEqualByComparingTo("25");
    }

    @Test
    void integrationMode_closesPasswordEndpoints_withoutRemovingLegacyHandlers() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unused@example.invalid\",\"password\":\"unused\"}"))
                .andExpect(status().isUnauthorized());
        Linked user = linked(Role.USER);
        mvc.perform(auth(post("/api/auth/register"), token(user.subject(), List.of("USER"), b -> {}))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unused@example.invalid\",\"password\":\"unused-password\"}"))
                .andExpect(status().isForbidden());
    }

    private Linked linked(Role storedRole) {
        User user = users.save(new User(UUID.randomUUID() + "@test.esep", "legacy-hash-preserved", storedRole));
        String subject = UUID.randomUUID().toString();
        identities.saveAndFlush(new UserIdentity(user, ISSUER, subject, "integration-test"));
        return new Linked(user, subject);
    }

    private static JWTClaimsSet.Builder claims(String sub, List<String> roles) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(sub).audience("esep-api")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(5))).claim("typ", "Bearer")
                .claim("resource_access", Map.of("esep-api", Map.of("roles", roles)));
    }

    private static String token(String sub, List<String> roles, Consumer<JWTClaimsSet.Builder> mutate) throws Exception {
        var claims = claims(sub, roles);
        mutate.accept(claims);
        return sign(claims, KEY);
    }

    private static String sign(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private record Linked(User user, String subject) {}
}
