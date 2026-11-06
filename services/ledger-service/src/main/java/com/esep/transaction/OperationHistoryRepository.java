package com.esep.transaction;

import com.esep.transaction.dto.OperationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class OperationHistoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public List<OperationResponse> find(HistoryFilter filter, int page, int size) {
        String sql = """
                SELECT e.id AS entry_id, e.transaction_id, t.type, e.direction, e.amount, a.currency,
                       e.account_id, c.account_id AS counterparty_account_id, e.created_at
                FROM ledger_entries e
                JOIN accounts a     ON a.id = e.account_id
                JOIN transactions t ON t.id = e.transaction_id
                LEFT JOIN ledger_entries c ON c.transaction_id = e.transaction_id AND c.id <> e.id
                """ + where(filter) + """
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = params(filter)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query(sql, params, (rs, i) -> new OperationResponse(
                rs.getLong("entry_id"),
                rs.getLong("transaction_id"),
                TransactionType.valueOf(rs.getString("type")),
                EntryDirection.valueOf(rs.getString("direction")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getLong("account_id"),
                rs.getObject("counterparty_account_id", Long.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    public long count(HistoryFilter filter) {
        String sql = """
                SELECT count(*)
                FROM ledger_entries e
                JOIN accounts a ON a.id = e.account_id
                """ + where(filter);
        Long total = jdbc.queryForObject(sql, params(filter), Long.class);
        return total == null ? 0 : total;
    }

    // optional filters are appended as fixed SQL fragments; values always go through bind parameters
    private static String where(HistoryFilter filter) {
        StringBuilder where = new StringBuilder("WHERE a.user_id = :userId\n");
        if (filter.accountId() != null) {
            where.append("  AND e.account_id = :accountId\n");
        }
        // calendar days -> instants by PostgreSQL's tzdata, exactly as the analytics reports do,
        // so a day in the history and the same day in a report always contain the same entries
        if (filter.fromDay() != null) {
            where.append("  AND e.created_at >= CAST(:fromDay AS timestamp) AT TIME ZONE :zone\n");
        }
        if (filter.toDay() != null) {
            where.append("  AND e.created_at < (CAST(:toDay AS timestamp) + INTERVAL '1 day') AT TIME ZONE :zone\n");
        }
        return where.toString();
    }

    private static MapSqlParameterSource params(HistoryFilter filter) {
        return new MapSqlParameterSource()
                .addValue("userId", filter.userId())
                .addValue("accountId", filter.accountId())
                .addValue("fromDay", filter.fromDay())
                .addValue("toDay", filter.toDay())
                .addValue("zone", filter.zone().getId());
    }

    /** Calendar days in {@code zone}, both inclusive and optional. */
    public record HistoryFilter(Long userId, Long accountId, LocalDate fromDay, LocalDate toDay, ZoneId zone) {
    }
}
