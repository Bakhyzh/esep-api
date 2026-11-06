package com.esep.profile;

import com.esep.support.IntegrationTest;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class UserAuthorityIntegrationTest extends IntegrationTest {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final String ISSUER;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("test-user").generate();
            JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",e->{byte[] b=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);
                try(var o=e.getResponseBody()){o.write(b);}});JWKS.start();
            ISSUER="http://127.0.0.1:"+JWKS.getAddress().getPort()+"/realms/esep";
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void oidc(DynamicPropertyRegistry r){r.add("esep.security.keycloak.issuer-uri",()->ISSUER);
        r.add("esep.security.keycloak.jwk-set-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");}
    @AfterAll static void close(){JWKS.stop(0);}
    @Autowired UserAuthority users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    @Test void concurrentFirstLoginsAllocateOneIdAndOneAtomicEvent() throws Exception {
        var jwt=jwt(UUID.randomUUID().toString(),UUID.randomUUID()+"@example.invalid");
        try(var pool=Executors.newFixedThreadPool(8)){
            var futures=pool.invokeAll(java.util.stream.IntStream.range(0,16).mapToObj(i->(Callable<Long>)()->users.current(jwt).id()).toList());
            long id=futures.getFirst().get();for(var f:futures)assertThat(f.get()).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id=?",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='users' AND column_name='password_hash'",Long.class)).isZero();
        }
    }
    @Test void existingEmailNeverLinksAndRollbackLeavesNoPartialProfile() {
        String email=UUID.randomUUID()+"@example.invalid";var first=users.current(jwt("a-"+email,email));
        assertThatThrownBy(()->users.current(jwt("b-"+email,email))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id=?",Long.class,first.id())).isEqualTo(1);
        String rolled=UUID.randomUUID()+"@example.invalid";
        tx.executeWithoutResult(s->{users.current(jwt("rollback-"+rolled,rolled));s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?",Long.class,rolled)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE subject=?",Long.class,"rollback-"+rolled)).isZero();
    }
    @Test void lifecycleVersionsEmitSnapshotsAndStaleUpdatesConflict() {
        var j=jwt(UUID.randomUUID().toString(),UUID.randomUUID()+"@example.invalid");var p=users.current(j);
        var updated=users.update(j,"Display",1);assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(()->users.update(j,"stale",1)).isInstanceOf(com.esep.common.exception.ConflictException.class);
        var operator=jwt(UUID.randomUUID().toString(),UUID.randomUUID()+"@example.invalid");
        users.status(operator,p.id(),"DISABLED");
        assertThatThrownBy(()->users.current(j)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        users.status(operator,p.id(),"ACTIVE");assertThat(users.current(j).version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",Long.class,p.id())).isEqualTo(4);
    }
    @Test void actualRs256ValidationRoleGateAndIgnoredUserIdClaim() throws Exception {
        String sub=UUID.randomUUID().toString();String email=sub+"@example.invalid";
        String raw=token(sub,email,List.of("USER"),ISSUER,KEY,"esep-api");
        var result=mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+raw)).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("USER")).andReturn();
        long id=((Number)com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.id")).longValue();
        assertThat(id).isNotEqualTo(999999999L);
        mvc.perform(post("/api/users/"+id+"/disable").header("Authorization","Bearer "+raw)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token(sub,email,List.of(),ISSUER,KEY,"esep-api"))).andExpect(status().isForbidden());
        for(String bad:List.of(token(sub,email,List.of("USER"),ISSUER+"x",KEY,"esep-api"),token(sub,email,List.of("USER"),ISSUER,KEY,"wrong"),
                token(sub,email,List.of("USER"),ISSUER,new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate(),"esep-api")))
            mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+bad)).andExpect(status().isUnauthorized());
    }
    static Jwt jwt(String sub,String email){return Jwt.withTokenValue("test").header("alg","RS256").issuer(ISSUER).subject(sub)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("email",email).claim("email_verified",true).build();}
    static String token(String sub,String email,List<String> roles,String issuer,RSAKey key,String aud)throws Exception{
        var b=new JWTClaimsSet.Builder().issuer(issuer).subject(sub).audience(aud).expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ","Bearer").claim("email",email).claim("email_verified",true).claim("local_user_id",999999999L)
            .claim("resource_access",Map.of("esep-api",Map.of("roles",roles))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),b);jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
}
