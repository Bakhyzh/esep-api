package com.esep.notification;

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
class NotificationIntegrationTest extends IntegrationTest {
    static final RSAKey KEY;static final HttpServer JWKS;static final String ISSUER;
    static {
        try{KEY=new RSAKeyGenerator(2048).keyID("notification-test").generate();JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",e->{byte[] bytes=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);try(var o=e.getResponseBody()){o.write(bytes);}});
            JWKS.start();ISSUER="http://127.0.0.1:"+JWKS.getAddress().getPort()+"/realms/esep";
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void oidc(DynamicPropertyRegistry r){r.add("esep.security.keycloak.issuer-uri",()->ISSUER);
        r.add("esep.security.keycloak.jwk-set-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");}
    @AfterAll static void close(){JWKS.stop(0);}
    @Autowired NotificationProcessor processor;
    @Autowired IdentityProjection identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    static long next=50000;
    static synchronized long id(){return ++next;}
    TransferCompletedEvent event(long from,long to){return new TransferCompletedEvent(1,"TRANSFER_COMPLETED",UUID.randomUUID(),id(),id(),from,id(),to,new BigDecimal("12.3456"),"KZT",Instant.now());}
    @Test void duplicateDeliveryAndReplayProduceOneNotificationPerRecipient()throws Exception {
        var e=event(id(),id());String json=mapper.writeValueAsString(e);
        kafka.send("esep.ledger.transfers.v1",String.valueOf(e.fromAccountId()),json).get();
        kafka.send("esep.ledger.transfers.v1",String.valueOf(e.fromAccountId()),json).get();
        kafka.send("esep.notifications.replay.v1",String.valueOf(e.fromAccountId()),json).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Long.class,e.eventId())).isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Long.class,e.eventId())).isEqualTo(1);
        var conflict=new TransferCompletedEvent(1,e.eventType(),e.eventId(),e.transactionId(),e.fromAccountId(),e.fromUserId(),e.toAccountId(),e.toUserId(),new BigDecimal("99"),e.currency(),e.occurredAt());
        assertThatThrownBy(()->processor.apply(conflict)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void processedMarkerAndNotificationsRollbackTogether(){
        var e=event(id(),id());tx.executeWithoutResult(s->{processor.apply(e);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Long.class,e.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Long.class,e.eventId())).isZero();
    }
    @Test void realTransientDatabaseFailureRetriesWithoutLosingOrDuplicatingNotification()throws Exception {
        jdbc.execute("CREATE SEQUENCE notification_fail_once");
        jdbc.execute("CREATE FUNCTION notification_fail_once() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF nextval('notification_fail_once')=1 THEN RAISE EXCEPTION 'synthetic transient failure' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER notification_fail_once BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION notification_fail_once()");
        try{
            var e=event(id(),id());kafka.send("esep.ledger.transfers.v1",String.valueOf(e.fromAccountId()),mapper.writeValueAsString(e)).get();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?",Long.class,e.eventId())).isEqualTo(2));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id=?",Long.class,e.eventId())).isEqualTo(1);
        }finally{jdbc.execute("DROP TRIGGER notification_fail_once ON notifications");jdbc.execute("DROP FUNCTION notification_fail_once()");jdbc.execute("DROP SEQUENCE notification_fail_once");}
    }
    @Test void independentlyValidatedOwnerCanReadAndMarkOnlyOwnNotifications()throws Exception{
        long alice=id(),bob=id();String a=linked(alice),b=linked(bob);var e=event(alice,bob);processor.apply(e);
        long own=jdbc.queryForObject("SELECT id FROM notifications WHERE event_id=? AND user_id=?",Long.class,e.eventId(),alice);
        long foreign=jdbc.queryForObject("SELECT id FROM notifications WHERE event_id=? AND user_id=?",Long.class,e.eventId(),bob);
        mvc.perform(get("/api/notifications").header("Authorization","Bearer "+a)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(own));
        mvc.perform(patch("/api/notifications/"+foreign+"/read").header("Authorization","Bearer "+a)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/notifications/"+own+"/read").header("Authorization","Bearer "+b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization","Bearer "+a)).andExpect(jsonPath("$.count").value(1));
        mvc.perform(patch("/api/notifications/"+own+"/read").header("Authorization","Bearer "+a)).andExpect(status().isOk());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization","Bearer "+a)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void malformedEventGoesToDedicatedDlt()throws Exception{
        String key=UUID.randomUUID().toString();var config=new java.util.HashMap<String,Object>();
        config.put("bootstrap.servers",kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id","notification-dlt-"+key);config.put("auto.offset.reset","earliest");
        config.put("key.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put("value.deserializer",org.apache.kafka.common.serialization.StringDeserializer.class);
        try(var c=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(config)){
            c.subscribe(List.of("esep.ledger.transfers.v1.notification.DLT"));kafka.send("esep.ledger.transfers.v1",key,"{malformed").get();
            boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!found && System.nanoTime()<deadline)for(var r:c.poll(Duration.ofMillis(200)))if(key.equals(r.key()))found=true;
            assertThat(found).isTrue();
        }
    }
    String linked(long user)throws Exception{
        String subject=UUID.randomUUID().toString();identities.apply(new IdentityEvent(1,"USER_IDENTITY_CHANGED",UUID.randomUUID(),user,ISSUER,subject,1,"ACTIVE",Instant.now()));
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("esep-api").expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("typ","Bearer").claim("resource_access",Map.of("esep-api",Map.of("roles",List.of("USER")))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(KEY));return jwt.serialize();
    }
}
