package com.esep.security;

import com.esep.principal.*;
import com.esep.support.IntegrationTest;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual RSA signatures and HTTP JWKS through the real Spring security chain. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LedgerSecurityIntegrationTest extends IntegrationTest {
    static final AtomicLong IDS = new AtomicLong(20000);
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", e -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");
                e.sendResponseHeaders(200,body.length);
                try (var out=e.getResponseBody()) { out.write(body); }
            });
            JWKS.start();
            ISSUER = "http://127.0.0.1:"+JWKS.getAddress().getPort()+"/realms/esep";
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void oidc(DynamicPropertyRegistry r) {
        r.add("esep.security.keycloak.issuer-uri",()->ISSUER);
        r.add("esep.security.keycloak.jwk-set-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");
    }
    @AfterAll static void close() { JWKS.stop(0); }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void independentValidationRejectsWrongSignatureClaimsAndHs256() throws Exception {
        String sub=linked();
        mvc.perform(get("/api/accounts").header("Authorization","Bearer "+token(sub,List.of("USER"), b->{})))
                .andExpect(status().isOk()).andExpect(cookie().doesNotExist("JSESSIONID"));
        List<Consumer<JWTClaimsSet.Builder>> bad=List.of(b->b.issuer(ISSUER+"-wrong"),b->b.audience("wrong"),
                b->b.expirationTime(Date.from(Instant.now().minusSeconds(180))),
                b->b.notBeforeTime(Date.from(Instant.now().plusSeconds(180))),b->b.expirationTime(null),
                b->b.subject(" "),b->b.claim("typ","ID"));
        for (var mutate:bad) mvc.perform(get("/api/accounts").header("Authorization","Bearer "+token(sub,List.of("USER"),mutate)))
                .andExpect(status().isUnauthorized());
        RSAKey attacker=new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate();
        mvc.perform(get("/api/accounts").header("Authorization","Bearer "+sign(claims(sub,List.of("ADMIN")),attacker)))
                .andExpect(status().isUnauthorized());
        SignedJWT hs=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims(sub,List.of("ADMIN")).build());
        hs.sign(new MACSigner(new byte[32]));
        mvc.perform(get("/api/accounts").header("Authorization","Bearer "+hs.serialize())).andExpect(status().isUnauthorized());
    }
    @Test void unknownRolelessForeignRolesAndForgedUserIdNeverProvision() throws Exception {
        long before=jdbc.queryForObject("SELECT count(*) FROM ledger_principals",Long.class);
        String unknown=token(UUID.randomUUID().toString(),List.of("ADMIN"), b->b.claim("userId",1).claim("email_verified",true));
        mvc.perform(get("/api/accounts").header("Authorization","Bearer "+unknown)).andExpect(status().isForbidden());
        String sub=linked();
        for (Object roles:List.of(Map.of(),"invalid",Map.of("other-api",Map.of("roles",List.of("ADMIN"))),
                Map.of("esep-api",Map.of("roles","ADMIN")))) {
            mvc.perform(get("/api/accounts").header("Authorization","Bearer "+token(sub,List.of(),b->b.claim("resource_access",roles))))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_principals",Long.class)).isEqualTo(before+1);
    }
    @Test void accountOwnershipRoleChecksAndApiContractsRemainCompatible() throws Exception {
        String alice=token(linked(),List.of("USER"),b->{}), bob=token(linked(),List.of("USER"),b->{}), admin=token(linked(),List.of("ADMIN"),b->{});
        var created=mvc.perform(post("/api/accounts").header("Authorization","Bearer "+alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"KZT\"}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        String location=created.getResponse().getHeader("Location");
        mvc.perform(get(location).header("Authorization","Bearer "+alice)).andExpect(status().isOk());
        mvc.perform(get(location).header("Authorization","Bearer "+bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/deposits").header("Authorization","Bearer "+alice).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // ADMIN does not own Alice's source account; even validation of a foreign source is hidden.
        long id=Long.parseLong(location.substring(location.lastIndexOf('/')+1));
        mvc.perform(post("/api/transfers").header("Authorization","Bearer "+admin).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccountId\":"+id+",\"toAccountId\":999999,\"amount\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/accounts']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/analytics/spending']").doesNotExist());
    }
    @Test void systemPrincipalCannotAuthenticateEvenWithErroneousBinding() throws Exception {
        String sub=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by) VALUES (9223372036854775807,?,?,'test-negative')",ISSUER,sub);
        mvc.perform(get("/api/accounts").header("Authorization","Bearer "+token(sub,List.of("ADMIN"),b->{})))
                .andExpect(status().isForbidden());
    }
    @Test @SuppressWarnings("unchecked")
    void publishedContractMatchesRuntimeFinancialOperations() throws Exception {
        String actual=mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        String declared=java.nio.file.Files.readString(java.nio.file.Path.of("contracts/ledger-api.openapi.json"));
        Map<String,Map<String,Object>> paths=com.jayway.jsonpath.JsonPath.read(actual,"$.paths");
        Map<String,Map<String,Object>> contract=com.jayway.jsonpath.JsonPath.read(declared,"$.paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(contract.keySet());
        paths.forEach((path,operations)-> {
            assertThat(operations.keySet()).isEqualTo(contract.get(path).keySet());
            operations.forEach((method,op)-> {
                Map<String,Object> operation=(Map<String,Object>) op;
                assertThat((Map<String,Object>)operation.get("responses")).containsKeys("400","401","403","500");
            });
        });
    }
    String linked() {
        long id=IDS.incrementAndGet();String sub=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ledger_principals(id,kind) VALUES (?,'HUMAN')",id);
        jdbc.update("INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by) VALUES (?,?,?,'test-approved')",id,ISSUER,sub);
        return sub;
    }
    static JWTClaimsSet.Builder claims(String sub,List<String> roles) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(sub).audience("esep-api")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ","Bearer").claim("resource_access",Map.of("esep-api",Map.of("roles",roles)));
    }
    static String token(String sub,List<String> roles,Consumer<JWTClaimsSet.Builder> mutate) throws Exception {
        var b=claims(sub,roles);mutate.accept(b);return sign(b,KEY);
    }
    static String sign(JWTClaimsSet.Builder b,RSAKey key) throws Exception {
        SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),b.build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
}
