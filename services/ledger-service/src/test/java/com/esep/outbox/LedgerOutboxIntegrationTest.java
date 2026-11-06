package com.esep.outbox;

import com.esep.account.*;
import com.esep.principal.*;
import com.esep.security.CurrentUser;
import com.esep.support.IntegrationTest;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL and Kafka, with a real unreachable broker during retry testing. */
class LedgerOutboxIntegrationTest extends IntegrationTest {
    static final AtomicLong IDS = new AtomicLong(40000);
    @Autowired TransactionService money;
    @Autowired LedgerPrincipalRepository principals;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxProperties properties;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;

    @Test void committedTransferReachesKafkaWithStableVersionedEventId() {
        // Publish earlier fixtures first: this test does not delete or truncate financial data.
        publisher(kafka).publishPending();
        var transfer=transfer();
        String eventId=jdbc.queryForObject("SELECT event_id::text FROM outbox_events WHERE aggregate_id=?",String.class,transfer.response().id());
        try (var consumer=new KafkaConsumer<String,String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getProducerFactory().getConfigurationProperties().get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
                ConsumerConfig.GROUP_ID_CONFIG,"ledger-check-"+UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class))) {
            consumer.subscribe(List.of(properties.topic()));
            publisher(kafka).publishPending();
            ConsumerRecord<String,String> found=null;
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(found==null && System.nanoTime()<deadline) {
                for(var r:consumer.poll(Duration.ofMillis(300))) {
                    if(new String(r.headers().lastHeader(OutboxPublisher.EVENT_ID_HEADER).value(),java.nio.charset.StandardCharsets.UTF_8).equals(eventId)) found=r;
                }
            }
            assertThat(found).isNotNull();
            var event=mapper.readValue(found.value(),TransferCompletedEvent.class);
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.eventId().toString()).isEqualTo(eventId);
            assertThat(event.transactionId()).isEqualTo(transfer.response().id());
            assertThat(event.amount()).isEqualByComparingTo("10.1250");
            assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id=?::uuid",Boolean.class,eventId)).isTrue();
        }
    }
    @Test void unavailableKafkaDoesNotRollbackMoneyAndRetryPublishesTheSameEvent() {
        publisher(kafka).publishPending();
        var transfer=transfer();
        long txId=transfer.response().id();
        String eventId=jdbc.queryForObject("SELECT event_id::text FROM outbox_events WHERE aggregate_id=?",String.class,txId);
        var config=new HashMap<>(kafka.getProducerFactory().getConfigurationProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,"127.0.0.1:1");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,500);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,500);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,1000);
        var factory=new DefaultKafkaProducerFactory<String,String>(config);
        var unavailable=new KafkaTemplate<String,String>(factory);
        try {
            publisher(unavailable).publishPending();
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE aggregate_id=?",Integer.class,txId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events WHERE aggregate_id=?",Boolean.class,txId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id=? AND status='COMPLETED'",Long.class,txId)).isEqualTo(1);
        } finally { factory.destroy(); }
        publisher(kafka).publishPending();
        assertThat(jdbc.queryForObject("SELECT event_id::text FROM outbox_events WHERE aggregate_id=?",String.class,txId)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id=?",Boolean.class,txId)).isTrue();
    }
    OutboxPublisher publisher(KafkaTemplate<String,String> broker) {
        return new OutboxPublisher(outbox,broker,new OutboxProperties(properties.topic(),Duration.ofSeconds(1),1000,Duration.ofSeconds(2),true),tx);
    }
    TransactionResult transfer() {
        var a=principals.save(new LedgerPrincipal(IDS.incrementAndGet()));
        var b=principals.save(new LedgerPrincipal(IDS.incrementAndGet()));
        var from=accounts.save(new Account(a,Currency.getInstance("KZT")));
        var to=accounts.save(new Account(b,Currency.getInstance("KZT")));
        money.deposit(new CurrentUser(a.getId(),Role.ADMIN),UUID.randomUUID().toString(),new DepositRequest(from.getId(),new BigDecimal("100")));
        return money.transfer(new CurrentUser(a.getId(),Role.USER),UUID.randomUUID().toString(),new TransferRequest(from.getId(),to.getId(),new BigDecimal("10.1250")));
    }
}
