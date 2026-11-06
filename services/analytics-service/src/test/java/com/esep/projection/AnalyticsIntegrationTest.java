package com.esep.projection;

import com.esep.identity.*;
import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AnalyticsIntegrationTest extends IntegrationTest {
    static final RSAKey KEY;static final HttpServer JWKS;static final String ISSUER;
    static {
        try{KEY=new RSAKeyGenerator(2048).keyID("analytics-test").generate();JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",e->{byte[] bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);try(var o=e.getResponseBody()){o.write(bytes);}});
            JWKS.start();ISSUER="http://127.0.0.1:"+JWKS.getAddress().getPort()+"/realms/esep";
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void oidc(DynamicPropertyRegistry r){r.add("esep.security.keycloak.issuer-uri",()->ISSUER);
        r.add("esep.security.keycloak.jwk-set-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");}
    @AfterAll static void close(){JWKS.stop(0);}
    @Autowired AnalyticsProjection projection;
    @Autowired IdentityProjection identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired com.esep.analytics.AnalyticsCache cache;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redis") org.testcontainers.containers.GenericContainer<?> redisContainer;
    @Autowired com.esep.analytics.AnalyticsRepository reports;

    @Test void redisOutageFallsBackToSqlWithoutLosingDurableInvalidation(){
        long owner=id();projection.postings(event(owner));
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            assertThat(cache.getOrLoad(owner,"outage","KZT",String.class,()->List.of("sql"))).containsExactly("sql");
            projection.postings(event(owner));
            assertThat(jdbc.queryForObject("SELECT generation FROM analytics_generations WHERE user_id=?",Long.class,owner)).isEqualTo(2);
        } finally {redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();}
        assertThat(cache.getOrLoad(owner,"outage","KZT",String.class,()->List.of("recovered"))).containsExactly("recovered");
    }
    @Test void allReportQueriesPreserveExactAmountsAndCalendarBoundaries(){
        long owner=id();projection.postings(event(owner));
        var day=LocalDate.parse("2026-11-06");
        var scope=new com.esep.analytics.AnalyticsRepository.ReportScope(owner,"KZT",day,day,ZoneId.of("Asia/Almaty"));
        assertThat(reports.spendingByPeriod(scope,com.esep.analytics.Period.DAY).getFirst().total()).isEqualByComparingTo("12.3456");
        assertThat(reports.topTransactions(scope,10).getFirst().amount()).isEqualByComparingTo("12.3456");
        assertThat(reports.movingAverage(scope,7).getFirst().total()).isEqualByComparingTo("12.3456");
        var month=new com.esep.analytics.AnalyticsRepository.ReportScope(owner,"KZT",day.withDayOfMonth(1),day.withDayOfMonth(30),ZoneId.of("UTC"));
        assertThat(reports.monthlyComparison(month).getFirst().total()).isEqualByComparingTo("12.3456");
    }
    @Test void explainAnalyzeUsesSelectiveDebitIndexOnSyntheticHistory(){
        long owner=id(),account=id(),transaction=id();
        jdbc.update("INSERT INTO analytics_accounts(id,user_id,type,currency,created_at) VALUES (?,?,'USER','USD',now())",account,owner);
        jdbc.update("INSERT INTO analytics_transactions(id,type,created_at,semantic_hash) VALUES (?,'TRANSFER',now(),'synthetic')",transaction);
        jdbc.update("INSERT INTO analytics_entries(id,transaction_id,account_id,direction,amount,created_at,semantic_hash) SELECT 900000000+g,?,?,'DEBIT',1.0001,'2026-01-01'::timestamptz+g*interval '1 hour','explain-synthetic' FROM generate_series(1,20000) g",transaction,account);
        jdbc.execute("ANALYZE analytics_entries");jdbc.execute("ANALYZE analytics_accounts");
        var plan=jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT sum(e.amount) FROM analytics_entries e JOIN analytics_accounts a ON a.id=e.account_id WHERE a.user_id=? AND a.currency='USD' AND e.direction='DEBIT' AND e.created_at>='2026-11-01'::timestamptz AND e.created_at<'2026-11-02'::timestamptz",String.class,owner);
        assertThat(String.join("\n",plan)).contains("ix_analytics_entries_debit_account_created","Execution Time:");
        System.out.println("SYNTHETIC_ANALYTICS_EXPLAIN\n"+String.join("\n",plan));
    }
    static long next=50000;
    static synchronized long id(){return ++next;}
    PostingEvent event(long owner){
        long n=id();var time=Instant.parse("2026-11-06T10:00:00Z");
        return new PostingEvent(1,PostingEvent.TYPE,"ledger-service",UUID.randomUUID(),n,"TRANSFER",time,List.of(
            new PostingEvent.Posting(n*2,n*2,owner,"USER","KZT","DEBIT","12.3456",time),
            new PostingEvent.Posting(n*2+1,n*2+1,id(),"USER","KZT","CREDIT","12.3456",time)));
    }
    @Test void duplicateLiveDeliveryAndBackfillRemainExact()throws Exception{
        long owner=id();var e=event(owner);String json=mapper.writeValueAsString(e);
        kafka.send("esep.ledger.postings.v1",String.valueOf(e.transactionId()),json).get();
        kafka.send("esep.ledger.postings.v1",String.valueOf(e.transactionId()),json).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_entries WHERE transaction_id=?",Long.class,e.transactionId())).isEqualTo(2));
        projection.postings(new PostingEvent(e.schemaVersion(),e.eventType(),e.producer(),UUID.randomUUID(),e.transactionId(),e.transactionType(),e.occurredAt(),e.postings()));
        assertThat(jdbc.queryForObject("SELECT generation FROM analytics_generations WHERE user_id=?",Long.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(CASE WHEN direction='DEBIT' THEN -amount ELSE amount END) FROM analytics_entries WHERE transaction_id=?",BigDecimal.class,e.transactionId())).isEqualByComparingTo("0");
        assertThatThrownBy(()->projection.postings(new PostingEvent(1,e.eventType(),e.producer(),e.eventId(),e.transactionId(),"DEPOSIT",e.occurredAt(),e.postings()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void accountVersionsRejectConflictsAndIgnoreDelayedEvents(){
        long account=id(),owner=id();var time=Instant.parse("2026-01-01T00:00:00Z");
        projection.account(new AccountEvent(1,AccountEvent.TYPE,"ledger-service",UUID.randomUUID(),account,owner,"USER","USD","CLOSED",3,time,time.plusSeconds(10)));
        projection.account(new AccountEvent(1,AccountEvent.TYPE,"ledger-service",UUID.randomUUID(),account,owner,"USER","USD","ACTIVE",0,time,null));
        assertThat(jdbc.queryForObject("SELECT status FROM analytics_accounts WHERE id=?",String.class,account)).isEqualTo("CLOSED");
        assertThatThrownBy(()->projection.account(new AccountEvent(1,AccountEvent.TYPE,"ledger-service",UUID.randomUUID(),account,owner+1,"USER","USD","CLOSED",4,time,time.plusSeconds(10)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projection.account(new AccountEvent(1,AccountEvent.TYPE,"ledger-service",UUID.randomUUID(),account,owner,"USER","USD","ACTIVE",3,time,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projection.account(new AccountEvent(1,AccountEvent.TYPE,"ledger-service",UUID.randomUUID(),account,owner,"USER","USD","CLOSED",3,time,time.plusSeconds(11)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void markerFactsAndGenerationRollbackAtomicallyAndUnbalancedRejected(){
        var e=event(id());tx.executeWithoutResult(s->{projection.postings(e);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Long.class,e.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_entries WHERE transaction_id=?",Long.class,e.transactionId())).isZero();
        var p=e.postings().getFirst();
        assertThatThrownBy(()->projection.postings(new PostingEvent(1,e.eventType(),e.producer(),UUID.randomUUID(),e.transactionId(),e.transactionType(),e.occurredAt(),List.of(p,p)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durableGenerationInvalidatesCachedReports()throws Exception{
        long owner=id();var e=event(owner);projection.postings(e);
        var a=cache.getOrLoad(owner,"test","KZT",String.class,()->List.of("first"));
        assertThat(a).containsExactly("first");
        assertThat(cache.getOrLoad(owner,"test","KZT",String.class,()->List.of("unexpected"))).containsExactly("first");
        projection.postings(event(owner));
        assertThat(cache.getOrLoad(owner,"test","KZT",String.class,()->List.of("fresh"))).containsExactly("fresh");
        assertThat(redis.keys("esep:analytics:v1:user:"+owner+":*")).hasSize(2);
    }
    @Test void signedOwnerSeesOnlyOwnExactReport()throws Exception{
        long alice=id(),bob=id();String a=linked(alice),b=linked(bob);projection.postings(event(alice));
        String route="/api/analytics/spending?currency=KZT&from=2026-11-06&to=2026-11-06";
        mvc.perform(get(route).header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("$[0].total").value(12.3456));
        mvc.perform(get(route).header("Authorization","Bearer "+b)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(route+"&userId="+alice).header("Authorization","Bearer "+b)).andExpect(status().isForbidden());
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void malformedEventGoesToDedicatedDlt()throws Exception{
        String key=UUID.randomUUID().toString();var config=new java.util.HashMap<String,Object>();
        config.put("bootstrap.servers",kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id","analytics-dlt-"+key);config.put("auto.offset.reset","earliest");
        config.put("key.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
        try(var c=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(config)){
            c.subscribe(List.of("esep.ledger.postings.v1.analytics.DLT"));kafka.send("esep.ledger.postings.v1",key,"{malformed").get();
            boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!found && System.nanoTime()<deadline)for(var r:c.poll(Duration.ofMillis(200)))if(key.equals(r.key()))found=true;
            assertThat(found).isTrue();
        }
    }
    @Test void concurrentDuplicateCommitsExactlyOneProjection()throws Exception{
        long owner=id();var e=event(owner);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<16;i++)jobs.add(executor.submit(()->projection.postings(e)));
            for(var job:jobs)job.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics_entries WHERE transaction_id=?",Long.class,e.transactionId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT generation FROM analytics_generations WHERE user_id=?",Long.class,owner)).isEqualTo(1);
    }
    String linked(long user)throws Exception{
        String subject=UUID.randomUUID().toString();identities.apply(new IdentityEvent(1,"USER_IDENTITY_CHANGED",UUID.randomUUID(),user,ISSUER,subject,1,"ACTIVE",Instant.now()));
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("esep-api").expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ","Bearer").claim("resource_access",Map.of("esep-api",Map.of("roles",List.of("USER")))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(KEY));return jwt.serialize();
    }
}
