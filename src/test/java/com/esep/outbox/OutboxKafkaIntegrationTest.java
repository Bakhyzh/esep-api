package com.esep.outbox;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.common.exception.BusinessRuleException;
import com.esep.security.CurrentUser;
import com.esep.support.IntegrationTest;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** End to end on real PostgreSQL + Kafka: transfer -> outbox -> topic -> consumer -> notifications. */
class OutboxKafkaIntegrationTest extends IntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ConsumerFactory<String, String> consumerFactory;
    @Autowired
    private OutboxProperties outboxProperties;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void committedTransfer_isPublishedAndBothUsersAreNotified() {
        Funded alice = fundedAccount("1000");
        Funded bob = fundedAccount("0");

        TransactionResult result = transactionService.transfer(alice.owner(), UUID.randomUUID().toString(),
                new TransferRequest(alice.accountId(), bob.accountId(), new BigDecimal("100.25")));
        long txId = result.response().id();

        // the outbox row was written in the transfer's transaction
        assertThat(outboxCount(txId)).isEqualTo(1);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                    "SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, txId))
                    .isTrue();
            assertThat(notificationTypes(alice.owner().id())).containsExactly("TRANSFER_SENT");
            assertThat(notificationTypes(bob.owner().id())).containsExactly("TRANSFER_RECEIVED");
        });
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE user_id = ?", String.class,
                bob.owner().id())).contains("100.25 KZT");
    }

    @Test
    void rolledBackTransfer_leavesNoOutboxEvent() {
        Funded alice = fundedAccount("10");
        Funded bob = fundedAccount("0");
        long before = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);

        assertThatThrownBy(() -> transactionService.transfer(alice.owner(), UUID.randomUUID().toString(),
                new TransferRequest(alice.accountId(), bob.accountId(), new BigDecimal("999"))))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(before);
    }

    @Test
    void sameEventDeliveredTwice_isProcessedOnce() throws Exception {
        Funded alice = fundedAccount("0");
        Funded bob = fundedAccount("0");
        TransferCompletedEvent event = event(alice, bob);
        String payload = jsonMapper.writeValueAsString(event);

        // simulates at-least-once: the publisher crashed after the send and sent the event again
        kafkaTemplate.send(outboxProperties.topic(), String.valueOf(alice.accountId()), payload).get();
        kafkaTemplate.send(outboxProperties.topic(), String.valueOf(alice.accountId()), payload).get();

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Long.class, event.eventId())).isEqualTo(1));
        // give the second copy time to arrive, then make sure it changed nothing
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",
                Long.class, event.eventId())).isEqualTo(2); // one for the sender, one for the receiver
    }

    @Test
    void malformedMessage_goesStraightToDeadLetterTopic() {
        String key = "poison-" + UUID.randomUUID();
        try (Consumer<String, String> dlt = deadLetterConsumer()) {
            kafkaTemplate.send(outboxProperties.topic(), key, "{not json");

            ConsumerRecord<String, String> dead = awaitRecord(dlt, key);
            assertThat(dead.value()).isEqualTo("{not json");
            assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("ListenerExecutionFailedException");
            assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("InvalidEventException");
        }
    }

    @Test
    void failingEvent_isRetriedThenDeadLettered_andLeavesNoTrace() {
        Funded alice = fundedAccount("0");
        TransferCompletedEvent bad = new TransferCompletedEvent(UUID.randomUUID(), 1L, alice.accountId(),
                alice.owner().id(), 999_999_999L, 999_999_999L, BigDecimal.ONE, "KZT", Instant.now());
        String key = "fk-" + bad.eventId();
        try (Consumer<String, String> dlt = deadLetterConsumer()) {
            // receiver user does not exist: the notification INSERT fails with an FK violation every time
            kafkaTemplate.send(outboxProperties.topic(), key, jsonMapper.writeValueAsString(bad));

            ConsumerRecord<String, String> dead = awaitRecord(dlt, key);
            assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("DataIntegrityViolationException");
        }
        // each attempt rolled back completely: neither the processed marker nor the sender's notification stayed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Long.class, bad.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",
                Long.class, bad.eventId())).isZero();
    }

    // --- helpers ---

    private Consumer<String, String> deadLetterConsumer() {
        Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(outboxProperties.deadLetterTopic()));
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(Consumer<String, String> consumer, String key) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(TIMEOUT).until(() -> {
            consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private TransferCompletedEvent event(Funded from, Funded to) {
        return new TransferCompletedEvent(UUID.randomUUID(), 1L, from.accountId(), from.owner().id(),
                to.accountId(), to.owner().id(), new BigDecimal("5"), "KZT", Instant.now());
    }

    private long outboxCount(long txId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, txId);
    }

    private List<String> notificationTypes(long userId) {
        return jdbc.queryForList("SELECT type FROM notifications WHERE user_id = ? ORDER BY id", String.class, userId);
    }

    private Funded fundedAccount(String balance) {
        User owner = userRepository.save(new User(UUID.randomUUID() + "@test.esep", "hash", Role.USER));
        Account account = accountRepository.save(new Account(owner, KZT));
        if (new BigDecimal(balance).signum() > 0) {
            User admin = userRepository.save(new User(UUID.randomUUID() + "@test.esep", "hash", Role.ADMIN));
            transactionService.deposit(new CurrentUser(admin.getId(), Role.ADMIN), UUID.randomUUID().toString(),
                    new DepositRequest(account.getId(), new BigDecimal(balance)));
        }
        return new Funded(account.getId(), new CurrentUser(owner.getId(), Role.USER));
    }

    private record Funded(long accountId, CurrentUser owner) {
    }
}
