package com.esep.transaction;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.common.exception.BusinessRuleException;
import com.esep.support.IntegrationTest;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransferConcurrencyTest extends IntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final int THREADS = 32;

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    void hundredParallelRandomTransfers_keepTotalMoneyAndNoNegativeBalances() throws Exception {
        List<Long> ids = createFundedAccounts(5, "1000");
        BigDecimal totalBefore = totalBalance(ids);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();

        Random random = new Random(42); // fixed seed: the same scenario on every run
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long from = ids.get(random.nextInt(ids.size()));
            long to = ids.get(random.nextInt(ids.size()));
            if (from == to) {
                to = ids.get((ids.indexOf(from) + 1) % ids.size());
            }
            // amounts up to 400 on balances of 1000: some transfers must be rejected
            TransferRequest request = new TransferRequest(from, to, new BigDecimal(1 + random.nextInt(400) + ".25"));
            tasks.add(() -> {
                try {
                    transactionService.transfer(UUID.randomUUID().toString(), request);
                    succeeded.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertThat(e).hasMessageContaining("Insufficient funds");
                    insufficientFunds.incrementAndGet();
                }
                return null;
            });
        }

        runAllAtOnce(tasks);

        assertThat(succeeded.get() + insufficientFunds.get()).isEqualTo(100);
        assertThat(succeeded.get()).isPositive();
        assertThat(totalBalance(ids)).isEqualByComparingTo(totalBefore);
        assertLedgerIsConsistent(ids);
    }

    @Test
    void oppositeTransfersBetweenTwoAccounts_doNotDeadlock() throws Exception {
        List<Long> ids = createFundedAccounts(2, "1000");
        long a = ids.get(0);
        long b = ids.get(1);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            // half A->B, half B->A at the same time: the classic deadlock scenario without lock ordering
            TransferRequest request = i % 2 == 0
                    ? new TransferRequest(a, b, new BigDecimal("10"))
                    : new TransferRequest(b, a, new BigDecimal("10"));
            tasks.add(() -> {
                transactionService.transfer(UUID.randomUUID().toString(), request);
                return null;
            });
        }

        runAllAtOnce(tasks); // a deadlock would surface here as an exception from Future.get()

        assertThat(balance(a)).isEqualByComparingTo("1000");
        assertThat(balance(b)).isEqualByComparingTo("1000");
        assertLedgerIsConsistent(ids);
    }

    @Test
    void sameIdempotencyKeyInParallel_movesMoneyOnlyOnce() throws Exception {
        List<Long> ids = createFundedAccounts(2, "1000");
        String key = "same-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(ids.get(0), ids.get(1), new BigDecimal("100"));
        List<TransactionResult> results = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                results.add(transactionService.transfer(key, request));
                return null;
            });
        }

        runAllAtOnce(tasks);

        assertThat(results).hasSize(20);
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(results).extracting(r -> r.response().id()).containsOnly(results.getFirst().response().id());
        assertThat(balance(ids.get(0))).isEqualByComparingTo("900");
        assertThat(balance(ids.get(1))).isEqualByComparingTo("1100");
        assertThat(jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyForDifferentRequestsInParallel_onlyOneWins() throws Exception {
        // different account pairs do not wait on each other's locks, so only the UNIQUE index stops the race
        List<Long> ids = createFundedAccounts(10, "100");
        String key = "race-" + UUID.randomUUID();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i += 2) {
            TransferRequest request = new TransferRequest(ids.get(i), ids.get(i + 1), new BigDecimal("10"));
            tasks.add(() -> {
                try {
                    transactionService.transfer(key, request);
                    succeeded.incrementAndGet();
                } catch (BusinessRuleException e) {
                    assertThat(e).hasMessageContaining("different request");
                    rejected.incrementAndGet();
                }
                return null;
            });
        }

        runAllAtOnce(tasks);

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(4);
        assertThat(totalBalance(ids)).isEqualByComparingTo("1000");
        assertLedgerIsConsistent(ids);
    }

    // --- helpers ---

    /** Starts all tasks at the same moment and rethrows the first unexpected error. */
    private void runAllAtOnce(List<Callable<Void>> tasks) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }
        startGate.countDown();
        for (Future<Void> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
    }

    private List<Long> createFundedAccounts(int count, String initialBalance) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = userRepository.save(new User(UUID.randomUUID() + "@test.esep", "hash", Role.USER));
            Account account = accountRepository.save(new Account(user, KZT));
            transactionService.deposit(UUID.randomUUID().toString(),
                    new DepositRequest(account.getId(), new BigDecimal(initialBalance)));
            ids.add(account.getId());
        }
        return ids;
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    private BigDecimal totalBalance(List<Long> ids) {
        return ids.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertLedgerIsConsistent(List<Long> ids) {
        assertThat(jdbc.queryForObject(
                "select count(*) from accounts where type = 'USER' and balance < 0", Long.class))
                .as("no negative user balances").isZero();

        assertThat(jdbc.queryForObject("""
                select count(*) from (
                    select transaction_id from ledger_entries
                    group by transaction_id
                    having sum(case direction when 'DEBIT' then -amount else amount end) <> 0
                ) unbalanced
                """, Long.class))
                .as("every transaction sums to zero").isZero();

        for (Long id : ids) {
            BigDecimal ledgerSum = jdbc.queryForObject("""
                    select coalesce(sum(case direction when 'DEBIT' then -amount else amount end), 0)
                    from ledger_entries where account_id = ?
                    """, BigDecimal.class, id);
            assertThat(balance(id)).as("balance of account %d equals its ledger sum", id)
                    .isEqualByComparingTo(ledgerSum);
        }
    }
}
