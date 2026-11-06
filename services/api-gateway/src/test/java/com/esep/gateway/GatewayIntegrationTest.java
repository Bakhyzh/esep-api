package com.esep.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP routing/JWKS/cryptography and Redis; business behavior is tested against real services by E2E. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayIntegrationTest.Infrastructure.class)
@ActiveProfiles("replicated")
class GatewayIntegrationTest {
    static final RSAKey KEY;
    static final AtomicReference<JWKSet> KEYS=new AtomicReference<>();
    static final HttpServer BACKEND;
    static final HttpServer SECOND;
    static final AtomicBoolean FIRST_HEALTHY=new AtomicBoolean(true), SECOND_HEALTHY=new AtomicBoolean(true);
    static final ExecutorService SERVER_THREADS=Executors.newCachedThreadPool();
    static final AtomicInteger WRITES=new AtomicInteger();
    static final AtomicBoolean FORGED_HEADER=new AtomicBoolean();
    static final AtomicBoolean BEARER_PRESENT=new AtomicBoolean();
    static final String BASE;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("initial").generate();KEYS.set(new JWKSet(KEY.toPublicJWK()));
            BACKEND=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);BACKEND.setExecutor(SERVER_THREADS);
            BACKEND.createContext("/jwks",e->{
                byte[] value=KEYS.get().toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,value.length);
                try(var stream=e.getResponseBody()){stream.write(value);}
            });
            BACKEND.createContext("/api",e->serve(e,"ledger-1"));
            BACKEND.createContext("/actuator/health/readiness",e->health(e,FIRST_HEALTHY));
            SECOND=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);SECOND.setExecutor(SERVER_THREADS);
            SECOND.createContext("/api",e->serve(e,"ledger-2"));
            SECOND.createContext("/actuator/health/readiness",e->health(e,SECOND_HEALTHY));
            SECOND.start();
            BACKEND.start();BASE="http://127.0.0.1:"+BACKEND.getAddress().getPort();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static void serve(com.sun.net.httpserver.HttpExchange e,String instance)throws java.io.IOException {
                e.getRequestBody().readAllBytes();
                if(e.getRequestURI().getPath().endsWith("/slow"))try{Thread.sleep(1500);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                if(e.getRequestMethod().equals("POST"))WRITES.incrementAndGet();
                FORGED_HEADER.set(e.getRequestHeaders().getFirst("X-User-Id")!=null);
                BEARER_PRESENT.set(Optional.ofNullable(e.getRequestHeaders().getFirst("Authorization")).orElse("").startsWith("Bearer "));
                e.getResponseHeaders().set("X-Ledger-Instance",instance);
                e.getResponseHeaders().set("Content-Type","application/json");
                if(e.getRequestHeaders().getFirst("Origin")!=null) e.getResponseHeaders().set("Access-Control-Allow-Origin",e.getRequestHeaders().getFirst("Origin"));
                e.getResponseHeaders().set("Idempotent-Replayed","true");
                e.getResponseHeaders().set("X-Correlation-ID",e.getRequestHeaders().getFirst("X-Correlation-ID"));
                byte[] body="{\"backend\":true}".getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(e.getRequestMethod().equals("POST")?201:200,body.length);
                try(var stream=e.getResponseBody()){stream.write(body);}
    }
    static void health(com.sun.net.httpserver.HttpExchange e,AtomicBoolean healthy)throws java.io.IOException {
        byte[] body=(healthy.get()?"{\"status\":\"UP\"}":"{\"status\":\"DOWN\"}").getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(healthy.get()?200:503,body.length);
        try(var stream=e.getResponseBody()){stream.write(body);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Infrastructure {
        @Bean @ServiceConnection(name="redis") GenericContainer<?> redis(){
            return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        for(String service:List.of("user","analytics","notification"))r.add("esep.gateway."+service+"-uri",()->BASE);
        r.add("esep.gateway.ledger-uri",()->"lb://ledger");
        r.add("spring.cloud.discovery.client.simple.instances.ledger[0].uri",()->BASE);
        r.add("spring.cloud.discovery.client.simple.instances.ledger[1].uri",()->"http://127.0.0.1:"+SECOND.getAddress().getPort());
        r.add("spring.cloud.loadbalancer.health-check.interval",()->"200ms");
        r.add("spring.cloud.loadbalancer.health-check.refetch-instances-interval",()->"500ms");
        r.add("esep.gateway.issuer-uri",()->BASE+"/realms/esep");r.add("esep.gateway.jwk-set-uri",()->BASE+"/jwks");
        r.add("esep.gateway.write-limit",()->3);r.add("esep.gateway.read-limit",()->100);
        r.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout",()->"500ms");
    }
    @LocalServerPort int port;
    @Autowired GenericContainer<?> redis;
    @AfterAll static void stop(){BACKEND.stop(0);SECOND.stop(0);SERVER_THREADS.shutdownNow();}
    record Result(int status,HttpHeaders headers,String body){}
    Result call(String path,String token,String method,Map<String,String> headers){
        var spec=WebClient.create("http://localhost:"+port).method(HttpMethod.valueOf(method)).uri(path)
                .headers(h->{if(token!=null)h.setBearerAuth(token);headers.forEach(h::set);});
        return spec.exchangeToMono(r->r.bodyToMono(String.class).defaultIfEmpty("")
                .map(body->new Result(r.statusCode().value(),r.headers().asHttpHeaders(),body))).block(Duration.ofSeconds(10));
    }
    String token(RSAKey key,String issuer,String audience,String role,Instant expires,Instant notBefore,String subject)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                .expirationTime(Date.from(expires)).notBeforeTime(Date.from(notBefore))
                .claim("typ","Bearer").claim("resource_access",Map.of("esep-api",Map.of("roles",List.of(role)))).build();
        var signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);
        signed.sign(new RSASSASigner(key));return signed.serialize();
    }
    String valid(String role,String subject)throws Exception{
        return token(KEY,BASE+"/realms/esep","esep-api",role,Instant.now().plusSeconds(300),Instant.now().minusSeconds(1),subject);
    }
    @Test void independentlyServedInstancesBalanceAndUnhealthyInstanceIsExcluded()throws Exception {
        String jwt=valid("USER",UUID.randomUUID().toString());
        java.util.function.Supplier<Set<String>> seen=()->{
            Set<String> labels=new HashSet<>();
            for(int i=0;i<6;i++){
                var response=call("/api/accounts",jwt,"GET",Map.of());assertThat(response.status()).isEqualTo(200);
                labels.add(response.headers().getFirst("X-Ledger-Instance"));
            }
            return labels;
        };
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(seen.get()).containsExactlyInAnyOrder("ledger-1","ledger-2"));
        SECOND_HEALTHY.set(false);
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(seen.get()).containsExactly("ledger-1"));
        } finally {SECOND_HEALTHY.set(true);}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(seen.get()).containsExactlyInAnyOrder("ledger-1","ledger-2"));
    }
    @Test void routesPreserveBearerAndCompatibilityWithoutTrustingUserHeaders()throws Exception{
        String jwt=valid("USER",UUID.randomUUID().toString());
        for(String path:List.of("/api/auth/me","/api/accounts","/api/transactions","/api/analytics/spending","/api/notifications")){
            var result=call(path,jwt,"GET",Map.of("X-User-Id","99","X-Correlation-ID","safe-test-id"));
            assertThat(result.status()).isEqualTo(200);assertThat(result.body()).contains("backend");
            assertThat(result.headers().getFirst("X-Correlation-ID")).isEqualTo("safe-test-id");
            assertThat(FORGED_HEADER.get()).isFalse();assertThat(BEARER_PRESENT.get()).isTrue();
        }
        assertThat(call("/api/transfers",jwt,"POST",Map.of("Idempotency-Key",UUID.randomUUID().toString())).status()).isEqualTo(201);
        assertThat(call("/internal/ledger/write-fence",valid("ADMIN","operator"),"POST",Map.of()).status()).isEqualTo(403);
        assertThat(call("/api/auth/login",jwt,"POST",Map.of()).status()).isEqualTo(404);
    }
    @Test void verifiesRealSignaturesClaimsRolesAndRejectsLegacy()throws Exception{
        String sub=UUID.randomUUID().toString();var now=Instant.now();
        assertThat(call("/api/accounts",null,"GET",Map.of()).status()).isEqualTo(401);
        var wrongKey=new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate();
        List<String> invalid=List.of(
            token(wrongKey,BASE+"/realms/esep","esep-api","USER",now.plusSeconds(300),now.minusSeconds(1),sub),
            token(KEY,BASE+"/realms/other","esep-api","USER",now.plusSeconds(300),now.minusSeconds(1),sub),
            token(KEY,BASE+"/realms/esep","other","USER",now.plusSeconds(300),now.minusSeconds(1),sub),
            token(KEY,BASE+"/realms/esep","esep-api","USER",now.minusSeconds(120),now.minusSeconds(500),sub),
            token(KEY,BASE+"/realms/esep","esep-api","USER",now.plusSeconds(900),now.plusSeconds(300),sub));
        for(String value:invalid)assertThat(call("/api/accounts",value,"GET",Map.of()).status()).isEqualTo(401);
        assertThat(call("/api/accounts",valid("UNRELATED",sub),"GET",Map.of()).status()).isEqualTo(403);
        assertThat(call("/api/accounts",valid("ADMIN",sub),"GET",Map.of()).status()).isEqualTo(200);
        var hs=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),new JWTClaimsSet.Builder().subject(sub).build());
        hs.sign(new MACSigner(new byte[32]));assertThat(call("/api/accounts",hs.serialize(),"GET",Map.of()).status()).isEqualTo(401);
    }
    @Test void refreshesJwksForNewRotatedKey()throws Exception{
        var rotated=new RSAKeyGenerator(2048).keyID("rotated").generate();
        KEYS.set(new JWKSet(List.of(KEY.toPublicJWK(),rotated.toPublicJWK())));
        String jwt=token(rotated,BASE+"/realms/esep","esep-api","USER",Instant.now().plusSeconds(300),Instant.now().minusSeconds(1),UUID.randomUUID().toString());
        assertThat(call("/api/accounts",jwt,"GET",Map.of()).status()).isEqualTo(200);
    }
    @Test void atomicRedisLimitHandlesConcurrentRequests()throws Exception{
        String jwt=valid("USER",UUID.randomUUID().toString());
        try(var workers=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<Result>>();for(int i=0;i<12;i++)jobs.add(workers.submit(()->call("/api/transfers",jwt,"POST",Map.of())));
            int allowed=0,limited=0;
            for(var future:jobs){var result=future.get(10,TimeUnit.SECONDS);if(result.status()==201)allowed++;if(result.status()==429){limited++;assertThat(result.headers().getFirst("Retry-After")).isNotBlank();}}
            assertThat(allowed).isEqualTo(3);assertThat(limited).isEqualTo(9);
        }
    }
    @Test void redisOutageRejectsWritesBeforeRoutingAndAllowsReads()throws Exception{
        String jwt=valid("USER",UUID.randomUUID().toString());int before=WRITES.get();
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try{
            assertThat(call("/api/transfers",jwt,"POST",Map.of()).status()).isEqualTo(503);
            assertThat(WRITES.get()).isEqualTo(before);
            assertThat(call("/api/accounts",jwt,"GET",Map.of()).status()).isEqualTo(200);
        }finally{redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();}
    }
    @Test void corsAndTimeoutBoundariesAreEnforced()throws Exception{
        var allowed=call("/api/accounts",null,"OPTIONS",Map.of("Origin","http://localhost:5173","Access-Control-Request-Method","POST","Access-Control-Request-Headers","authorization,idempotency-key"));
        assertThat(allowed.status()).isEqualTo(200);assertThat(allowed.headers().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
        assertThat(call("/api/accounts",null,"OPTIONS",Map.of("Origin","https://untrusted.invalid","Access-Control-Request-Method","POST")).status()).isEqualTo(403);
        var actual=call("/api/accounts",valid("USER",UUID.randomUUID().toString()),"GET",Map.of("Origin","http://localhost:5173"));
        assertThat(actual.status()).isEqualTo(200);
        assertThat(actual.headers().get("Access-Control-Allow-Origin")).containsExactly("http://localhost:5173");
        String jwt=valid("USER",UUID.randomUUID().toString());
        int oversized=WebClient.create("http://localhost:"+port).post().uri("/api/transfers")
                .headers(h->h.setBearerAuth(jwt)).contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(70000))
                .exchangeToMono(r->r.releaseBody().thenReturn(r.statusCode().value())).block(Duration.ofSeconds(10));
        assertThat(oversized).isEqualTo(413);
        int before=WRITES.get();
        int chunked=WebClient.create("http://localhost:"+port).post().uri("/api/transfers")
                .headers(h->h.setBearerAuth(jwt)).contentType(MediaType.APPLICATION_JSON)
                .body(reactor.core.publisher.Flux.just("x".repeat(35000),"x".repeat(35000)),String.class)
                .exchangeToMono(r->r.releaseBody().thenReturn(r.statusCode().value())).block(Duration.ofSeconds(10));
        assertThat(chunked).isEqualTo(413);assertThat(WRITES.get()).isEqualTo(before);
        assertThat(call("/api/accounts/slow",valid("USER",UUID.randomUUID().toString()),"GET",Map.of()).status()).isEqualTo(504);
    }
}
