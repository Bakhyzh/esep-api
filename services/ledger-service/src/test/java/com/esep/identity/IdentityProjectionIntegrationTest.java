package com.esep.identity;
import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties={"esep.identity.enabled=true","spring.kafka.consumer.auto-offset-reset=earliest"})
class IdentityProjectionIntegrationTest extends IntegrationTest {
    @Autowired IdentityProjection projection;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired JsonMapper mapper;
    long id(){return 1000000L+new java.security.SecureRandom().nextInt(1000000000);}
    IdentityEvent event(long id,String subject,long version,String status){return new IdentityEvent(1,"USER_IDENTITY_CHANGED",UUID.randomUUID(),id,
        "http://localhost:8180/realms/esep",subject,version,status,Instant.now());}
    @Test void duplicatesDelayedAndOutOfOrderSnapshotsNeverReactivateDisabledUser(){
        long id=id();String sub=UUID.randomUUID().toString();var newest=event(id,sub,3,"DISABLED");
        projection.apply(newest);projection.apply(newest);projection.apply(event(id,sub,1,"ACTIVE"));projection.apply(event(id,sub,2,"ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT status FROM identity_projection_state WHERE user_id=?",String.class,id)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_processed_events WHERE event_id=?",Long.class,newest.eventId())).isEqualTo(1);
        assertThatThrownBy(()->projection.apply(new IdentityEvent(1,newest.eventType(),newest.eventId(),id,newest.issuer(),sub,4,"ACTIVE",newest.occurredAt())))
            .isInstanceOf(IllegalArgumentException.class);
        projection.apply(event(id,sub,4,"ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT status FROM identity_projection_state WHERE user_id=?",String.class,id)).isEqualTo("ACTIVE");
    }
    @Test void existingUnmanagedPrincipalAndConflictingBindingsCannotBeTakenOver(){
        long historical=id();jdbc.update("INSERT INTO ledger_principals(id,kind) VALUES (?,'HUMAN')",historical);
        var takeover=event(historical,"attacker",1,"ACTIVE");
        assertThatThrownBy(()->projection.apply(takeover)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_identity_bindings WHERE user_id=?",Long.class,historical)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_processed_events WHERE event_id=?",Long.class,takeover.eventId())).isZero();
        long first=id(),second=id();String sub=UUID.randomUUID().toString();projection.apply(event(first,sub,1,"ACTIVE"));
        assertThatThrownBy(()->projection.apply(event(second,sub,1,"ACTIVE"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_principals WHERE id=?",Long.class,second)).isZero();
        assertThatThrownBy(()->projection.apply(event(Long.MAX_VALUE,"system",1,"ACTIVE"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void realKafkaConsumerPersistsSnapshotAndDuplicateOnce() throws Exception {
        long id=id();var e=event(id,UUID.randomUUID().toString(),1,"ACTIVE");String json=mapper.writeValueAsString(e);
        kafka.send("esep.users.v1",String.valueOf(id),json).get();kafka.send("esep.users.v1",String.valueOf(id),json).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM identity_projection_state WHERE user_id=?",Long.class,id)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_processed_events WHERE event_id=?",Long.class,e.eventId())).isEqualTo(1);
    }
    @Test void malformedIdentityIsDurablyRoutedToDedicatedDlt() throws Exception {
        String key=UUID.randomUUID().toString();
        var config=new java.util.HashMap<String,Object>();
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getProducerFactory().getConfigurationProperties().get(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG,"dlt-check-"+key);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,org.apache.kafka.common.serialization.StringDeserializer.class);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(config)) {
            consumer.subscribe(java.util.List.of("esep.users.v1.ledger.DLT"));
            kafka.send("esep.users.v1",key,"{malformed").get();
            boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!found && System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(200)))if(key.equals(record.key())) {
                    assertThat(record.value()).isEqualTo("{malformed");found=true;
                }
            }
            assertThat(found).isTrue();
        }
    }

}
