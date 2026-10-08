package com.esep.analytics;

import com.esep.analytics.dto.MonthlyComparison;
import com.esep.analytics.dto.MovingAveragePoint;
import com.esep.analytics.dto.SpendingPoint;
import com.esep.analytics.dto.TopTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reports in plain SQL: window functions, CTEs and generate_series cannot be expressed in JPQL,
 * and reports need numbers, not managed entities.
 * <p>
 * "Spending" = DEBIT entries on the user's accounts in one currency (money that left the user).
 * All filters go through bind parameters (:name), never string concatenation -> no SQL injection.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsRepository {

    // shared filter; matches the partial index ix_ledger_entries_debit_account_created (V6)
    private static final String USER_DEBITS = """
            FROM ledger_entries e
            JOIN accounts a ON a.id = e.account_id
            WHERE a.user_id = :userId
              AND a.currency = :currency
              AND e.direction = 'DEBIT'
              AND e.created_at >= :from
              AND e.created_at < :to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<SpendingPoint> spendingByPeriod(ReportScope scope, Period period) {
        String sql = """
                SELECT date_trunc(:unit, e.created_at AT TIME ZONE :zone)::date AS period_start,
                       SUM(e.amount)                                           AS total,
                       COUNT(*)                                                AS operations
                """ + USER_DEBITS + """
                GROUP BY period_start
                ORDER BY period_start
                """;
        return jdbc.query(sql, scope.params().addValue("unit", period.sqlUnit()),
                (rs, i) -> new SpendingPoint(
                        rs.getObject("period_start", LocalDate.class),
                        rs.getBigDecimal("total"),
                        rs.getLong("operations")));
    }

    public List<TopTransaction> topTransactions(ReportScope scope, int limit) {
        // DENSE_RANK is computed over all matching rows before LIMIT cuts the result
        String sql = """
                SELECT DENSE_RANK() OVER (ORDER BY e.amount DESC) AS rnk,
                       e.transaction_id,
                       e.amount,
                       c.account_id AS to_account_id,
                       e.created_at
                FROM ledger_entries e
                JOIN accounts a       ON a.id = e.account_id
                JOIN ledger_entries c ON c.transaction_id = e.transaction_id AND c.direction = 'CREDIT'
                WHERE a.user_id = :userId
                  AND a.currency = :currency
                  AND e.direction = 'DEBIT'
                  AND e.created_at >= :from
                  AND e.created_at < :to
                ORDER BY e.amount DESC, e.transaction_id
                LIMIT :limit
                """;
        return jdbc.query(sql, scope.params().addValue("limit", limit),
                (rs, i) -> new TopTransaction(
                        rs.getInt("rnk"),
                        rs.getLong("transaction_id"),
                        rs.getBigDecimal("amount"),
                        rs.getLong("to_account_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    /**
     * Daily totals + moving average over the last {@code window} DAYS.
     * Days without spending are filled with 0 by generate_series: otherwise "ROWS BETWEEN 6 PRECEDING"
     * would mean "6 previous days WITH spending", not "6 previous calendar days".
     * The scope is widened by (window - 1) days at the start, so the first reported day
     * already averages a full window; those extra days are cut off in the outer query.
     */
    public List<MovingAveragePoint> movingAverage(ReportScope scope, int window) {
        ReportScope widened = scope.withFromDay(scope.fromDay().minusDays(window - 1L));
        String sql = """
                WITH days AS (
                    SELECT CAST(d AS date) AS day
                    FROM generate_series(CAST(:fromDay AS date), CAST(:toDay AS date), INTERVAL '1 day') AS d
                ),
                daily AS (
                    SELECT CAST(e.created_at AT TIME ZONE :zone AS date) AS day,
                           SUM(e.amount)                                 AS total
                """ + USER_DEBITS.indent(4) + """
                    GROUP BY 1
                ),
                averaged AS (
                    SELECT d.day,
                           COALESCE(s.total, 0) AS total,
                           ROUND(AVG(COALESCE(s.total, 0))
                                 OVER (ORDER BY d.day ROWS BETWEEN :preceding PRECEDING AND CURRENT ROW), 4)
                               AS moving_average
                    FROM days d
                    LEFT JOIN daily s ON s.day = d.day
                )
                SELECT day, total, moving_average
                FROM averaged
                WHERE day >= :reportFromDay
                ORDER BY day
                """;
        MapSqlParameterSource params = widened.params()
                .addValue("preceding", window - 1)
                .addValue("reportFromDay", scope.fromDay());
        return jdbc.query(sql, params,
                (rs, i) -> new MovingAveragePoint(
                        rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("moving_average")));
    }

    /**
     * Monthly totals compared with the previous month (CTE + LAG).
     * One extra month is loaded before the first reported one, so LAG has a value for it.
     */
    public List<MonthlyComparison> monthlyComparison(ReportScope scope) {
        String sql = """
                WITH months AS (
                    SELECT CAST(m AS date) AS month
                    FROM generate_series(CAST(:fromDay AS date), CAST(:toDay AS date), INTERVAL '1 month') AS m
                ),
                monthly AS (
                    SELECT CAST(date_trunc('month', e.created_at AT TIME ZONE :zone) AS date) AS month,
                           SUM(e.amount)                                                    AS total
                """ + USER_DEBITS.indent(4) + """
                    GROUP BY 1
                ),
                compared AS (
                    SELECT m.month,
                           COALESCE(x.total, 0)                                   AS total,
                           LAG(COALESCE(x.total, 0)) OVER (ORDER BY m.month)      AS previous_total
                    FROM months m
                    LEFT JOIN monthly x ON x.month = m.month
                )
                SELECT month,
                       total,
                       previous_total,
                       total - previous_total                                          AS change,
                       ROUND(100 * (total - previous_total) / NULLIF(previous_total, 0), 2) AS change_percent
                FROM compared
                WHERE month >= :reportFromDay
                ORDER BY month
                """;
        ReportScope widened = scope.withFromDay(scope.fromDay().minusMonths(1));
        MapSqlParameterSource params = widened.params().addValue("reportFromDay", scope.fromDay());
        return jdbc.query(sql, params,
                (rs, i) -> new MonthlyComparison(
                        YearMonth.from(rs.getObject("month", LocalDate.class)),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("previous_total"),
                        rs.getBigDecimal("change"),
                        rs.getBigDecimal("change_percent")));
    }

    /**
     * Whose money, which currency, which calendar days (inclusive) in which time zone.
     * Days are converted to UTC instants here, so the index on created_at can be used directly.
     */
    public record ReportScope(Long userId, String currency, LocalDate fromDay, LocalDate toDay, ZoneId zone) {

        ReportScope withFromDay(LocalDate newFromDay) {
            return new ReportScope(userId, currency, newFromDay, toDay, zone);
        }

        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("currency", currency)
                    .addValue("zone", zone.getId())
                    .addValue("fromDay", fromDay)
                    .addValue("toDay", toDay)
                    .addValue("from", utc(fromDay.atStartOfDay(zone).toInstant()))
                    // exclusive upper bound: start of the day AFTER toDay
                    .addValue("to", utc(toDay.plusDays(1).atStartOfDay(zone).toInstant()));
        }

        private static OffsetDateTime utc(Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
    }
}
