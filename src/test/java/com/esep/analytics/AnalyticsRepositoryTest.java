package com.esep.analytics;

import com.esep.analytics.AnalyticsRepository.ReportScope;
import com.esep.analytics.dto.MonthlyComparison;
import com.esep.analytics.dto.MovingAveragePoint;
import com.esep.analytics.dto.SpendingPoint;
import com.esep.analytics.dto.TopTransaction;
import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Ledger rows are inserted with fixed timestamps via JDBC, so every expected number is exact. */
class AnalyticsRepositoryTest extends IntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty"); // UTC+5

    @Autowired
    private AnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long kztAccount;
    private long usdAccount;
    private long otherAccount;

    @BeforeEach
    void createAccounts() {
        userId = insertUser();
        long otherUser = insertUser();
        kztAccount = insertAccount(userId, "KZT");
        usdAccount = insertAccount(userId, "USD");
        otherAccount = insertAccount(otherUser, "KZT");
    }

    @Test
    void spendingByDay_sumsOnlyDebitsOfTheUserInTheCurrency() {
        transfer(kztAccount, otherAccount, "100", "2026-03-01T10:00:00Z");
        transfer(kztAccount, otherAccount, "50.5", "2026-03-01T15:00:00Z");
        transfer(kztAccount, otherAccount, "20", "2026-03-03T09:00:00Z");
        transfer(otherAccount, kztAccount, "999", "2026-03-01T12:00:00Z"); // incoming: not spending
        transfer(usdAccount, insertAccount(insertUser(), "USD"), "7", "2026-03-01T12:00:00Z"); // other currency

        List<SpendingPoint> result = repository.spendingByPeriod(scope("2026-03-01", "2026-03-31", UTC), Period.DAY);

        assertThat(result)
                .extracting(SpendingPoint::periodStart, p -> p.total().stripTrailingZeros().toPlainString(), SpendingPoint::operations)
                .containsExactly(
                        tuple(LocalDate.parse("2026-03-01"), "150.5", 2L),
                        tuple(LocalDate.parse("2026-03-03"), "20", 1L));
    }

    @Test
    void spendingByDay_usesTheRequestedTimeZoneForDayBoundaries() {
        // 20:00 UTC on March 31 is already 01:00 on April 1 in Almaty
        transfer(kztAccount, otherAccount, "10", "2026-03-31T20:00:00Z");

        assertThat(repository.spendingByPeriod(scope("2026-03-01", "2026-04-30", UTC), Period.MONTH))
                .extracting(SpendingPoint::periodStart).containsExactly(LocalDate.parse("2026-03-01"));
        assertThat(repository.spendingByPeriod(scope("2026-03-01", "2026-04-30", ALMATY), Period.MONTH))
                .extracting(SpendingPoint::periodStart).containsExactly(LocalDate.parse("2026-04-01"));
    }

    @Test
    void topTransactions_orderedByAmountWithDenseRankAndReceiver() {
        long receiver2 = insertAccount(insertUser(), "KZT");
        transfer(kztAccount, otherAccount, "300", "2026-03-02T10:00:00Z");
        transfer(kztAccount, receiver2, "300", "2026-03-03T10:00:00Z");
        transfer(kztAccount, otherAccount, "100", "2026-03-04T10:00:00Z");
        transfer(kztAccount, otherAccount, "5", "2026-03-05T10:00:00Z");

        List<TopTransaction> top = repository.topTransactions(scope("2026-03-01", "2026-03-31", UTC), 3);

        assertThat(top).extracting(TopTransaction::rank).containsExactly(1, 1, 2);
        assertThat(top).extracting(TopTransaction::toAccountId).containsExactly(otherAccount, receiver2, otherAccount);
        assertThat(top.get(2).amount()).isEqualByComparingTo("100");
    }

    @Test
    void movingAverage_fillsEmptyDaysWithZeroAndUsesDaysBeforeTheRange() {
        transfer(kztAccount, otherAccount, "30", "2026-03-01T10:00:00Z"); // before the range, inside the window
        transfer(kztAccount, otherAccount, "60", "2026-03-03T10:00:00Z");

        List<MovingAveragePoint> result = repository.movingAverage(scope("2026-03-03", "2026-03-05", UTC), 3);

        // window of 3 calendar days: [Mar 1..3] = (30+0+60)/3, [Mar 2..4] = 60/3, [Mar 3..5] = 60/3
        assertThat(result)
                .extracting(MovingAveragePoint::day, p -> p.total().intValue(), p -> p.movingAverage().toPlainString())
                .containsExactly(
                        tuple(LocalDate.parse("2026-03-03"), 60, "30.0000"),
                        tuple(LocalDate.parse("2026-03-04"), 0, "20.0000"),
                        tuple(LocalDate.parse("2026-03-05"), 0, "20.0000"));
    }

    @Test
    void monthlyComparison_comparesWithPreviousMonthAndHandlesZero() {
        transfer(kztAccount, otherAccount, "200", "2026-01-15T10:00:00Z"); // the month before the report
        transfer(kztAccount, otherAccount, "100", "2026-02-10T10:00:00Z");
        transfer(kztAccount, otherAccount, "150", "2026-04-10T10:00:00Z");

        List<MonthlyComparison> result = repository.monthlyComparison(
                scope(YearMonth.of(2026, 2).atDay(1).toString(), YearMonth.of(2026, 4).atEndOfMonth().toString(), UTC));

        assertThat(result).extracting(MonthlyComparison::month)
                .containsExactly(YearMonth.of(2026, 2), YearMonth.of(2026, 3), YearMonth.of(2026, 4));
        MonthlyComparison feb = result.get(0);
        assertThat(feb.previousTotal()).isEqualByComparingTo("200");
        assertThat(feb.changePercent()).isEqualByComparingTo("-50.00");
        MonthlyComparison mar = result.get(1);
        assertThat(mar.total()).isEqualByComparingTo("0");
        assertThat(mar.changePercent()).isEqualByComparingTo("-100.00");
        MonthlyComparison apr = result.get(2);
        assertThat(apr.change()).isEqualByComparingTo("150");
        assertThat(apr.changePercent()).as("previous month was 0: no percent").isNull();
    }

    // --- data helpers ---

    private ReportScope scope(String from, String to, ZoneId zone) {
        return new ReportScope(userId, "KZT", LocalDate.parse(from), LocalDate.parse(to), zone);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', 'USER') RETURNING id",
                Long.class, UUID.randomUUID() + "@test.esep");
    }

    private long insertAccount(long ownerId, String currency) {
        return jdbc.queryForObject("INSERT INTO accounts (user_id, currency) VALUES (?, ?) RETURNING id",
                Long.class, ownerId, currency);
    }

    private void transfer(long from, long to, String amount, String at) {
        Timestamp createdAt = Timestamp.from(Instant.parse(at));
        long creator = jdbc.queryForObject("SELECT user_id FROM accounts WHERE id = ?", Long.class, from);
        long txId = jdbc.queryForObject("""
                INSERT INTO transactions (type, status, idempotency_key, request_hash, created_by, created_at)
                VALUES ('TRANSFER', 'COMPLETED', ?, 'test', ?, ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), creator, createdAt);
        String entry = "INSERT INTO ledger_entries (transaction_id, account_id, amount, direction, created_at) VALUES (?, ?, ?, ?, ?)";
        jdbc.update(entry, txId, from, new BigDecimal(amount), "DEBIT", createdAt);
        jdbc.update(entry, txId, to, new BigDecimal(amount), "CREDIT", createdAt);
    }
}
