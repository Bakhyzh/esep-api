package com.esep.analytics;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.analytics.AnalyticsService.ReportRequest;
import com.esep.analytics.dto.SpendingPoint;
import com.esep.security.CurrentUser;
import com.esep.support.IntegrationTest;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransferRequest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache-aside on a real Redis: hit, then invalidation after a committed transfer. */
class AnalyticsCacheIntegrationTest extends IntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void reportIsCachedAndInvalidatedByTheNextTransfer() {
        CurrentUser alice = newUser(Role.USER);
        CurrentUser admin = newUser(Role.ADMIN);
        long aliceAccount = account(alice);
        long bobAccount = account(newUser(Role.USER));
        transactionService.deposit(admin, UUID.randomUUID().toString(), new DepositRequest(aliceAccount, new BigDecimal("1000")));
        transfer(alice, aliceAccount, bobAccount, "100");   // generation 0 -> 1
        ReportRequest request = new ReportRequest(null, "KZT", null, null, ZoneId.of("UTC"));

        assertThat(total(analyticsService.spending(alice, request, Period.MONTH))).isEqualByComparingTo("100");
        assertThat(redis.keys("analytics:" + alice.id() + ":v1:spending:*")).hasSize(1);

        // a DEBIT written behind the service's back: a cache hit must NOT see it
        insertDebitDirectly(aliceAccount, bobAccount, alice.id(), "7");
        assertThat(total(analyticsService.spending(alice, request, Period.MONTH))).isEqualByComparingTo("100");

        // a real transfer bumps the generation after commit -> the next read goes to the database
        transfer(alice, aliceAccount, bobAccount, "50");
        assertThat(redis.opsForValue().get("analytics:" + alice.id() + ":generation")).isEqualTo("2");
        assertThat(total(analyticsService.spending(alice, request, Period.MONTH))).isEqualByComparingTo("157");
    }

    @Test
    void receiversReportsAreNotInvalidated() {
        CurrentUser alice = newUser(Role.USER);
        CurrentUser bob = newUser(Role.USER);
        CurrentUser admin = newUser(Role.ADMIN);
        long aliceAccount = account(alice);
        long bobAccount = account(bob);
        transactionService.deposit(admin, UUID.randomUUID().toString(), new DepositRequest(aliceAccount, new BigDecimal("100")));

        transfer(alice, aliceAccount, bobAccount, "10");

        assertThat(redis.opsForValue().get("analytics:" + bob.id() + ":generation")).isNull();
    }

    private void transfer(CurrentUser owner, long from, long to, String amount) {
        transactionService.transfer(owner, UUID.randomUUID().toString(), new TransferRequest(from, to, new BigDecimal(amount)));
    }

    private static BigDecimal total(List<SpendingPoint> points) {
        return points.stream().map(SpendingPoint::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CurrentUser newUser(Role role) {
        User user = userRepository.save(new User(UUID.randomUUID() + "@test.esep", "hash", role));
        return new CurrentUser(user.getId(), role);
    }

    private long account(CurrentUser owner) {
        return accountRepository.save(new Account(userRepository.getReferenceById(owner.id()), KZT)).getId();
    }

    private void insertDebitDirectly(long from, long to, long creator, String amount) {
        long txId = jdbc.queryForObject("""
                INSERT INTO transactions (type, status, idempotency_key, request_hash, created_by)
                VALUES ('TRANSFER', 'COMPLETED', ?, 'test', ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), creator);
        String entry = "INSERT INTO ledger_entries (transaction_id, account_id, amount, direction) VALUES (?, ?, ?, ?)";
        jdbc.update(entry, txId, from, new BigDecimal(amount), "DEBIT");
        jdbc.update(entry, txId, to, new BigDecimal(amount), "CREDIT");
    }
}
