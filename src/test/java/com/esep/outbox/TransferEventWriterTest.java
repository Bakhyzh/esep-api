package com.esep.outbox;

import com.esep.account.Account;
import com.esep.transaction.LedgerTransaction;
import com.esep.user.Role;
import com.esep.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransferEventWriterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private OutboxRepository outboxRepository;

    @Test
    void eventDescribesTheTransferAndIsKeyedBySenderAccount() {
        Account from = account(10L, user(1L), "500.0000");
        Account to = account(20L, user(2L), "0.0000");
        LedgerTransaction tx = LedgerTransaction.transfer(user(1L), "key", "hash", from, to, new BigDecimal("125.5"));
        ReflectionTestUtils.setField(tx, "id", 77L);

        TransferCompletedEvent event = new TransferEventWriter(outboxRepository, jsonMapper).transferCompleted(tx, from, to);

        assertThat(event.transactionId()).isEqualTo(77L);
        assertThat(event.fromAccountId()).isEqualTo(10L);
        assertThat(event.toUserId()).isEqualTo(2L);
        assertThat(event.amount()).isEqualByComparingTo("125.5");
        assertThat(event.currency()).isEqualTo("KZT");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).append(eq(event.eventId()), eq("transaction"), eq(77L),
                eq(TransferCompletedEvent.TYPE), eq("10"), payload.capture());
        // the stored payload is exactly what the consumer will parse
        assertThat(jsonMapper.readValue(payload.getValue(), TransferCompletedEvent.class)).isEqualTo(event);
    }

    private static Account account(long id, User owner, String balance) {
        Account account = new Account(owner, Currency.getInstance("KZT"));
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        return account;
    }

    private static User user(long id) {
        User user = new User("user" + id + "@esep.dev", "hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
