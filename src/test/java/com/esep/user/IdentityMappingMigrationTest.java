package com.esep.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Upgrade a real V7 database containing historical money; no clean/reset/old SQL edits. */
@Testcontainers
class IdentityMappingMigrationTest {
    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void v7ToV8_preservesUsersMoneyAndConstraints_andAddsExplicitIdentityUniqueness() throws Exception {
        flyway("7").migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var sql = c.createStatement()) {
            sql.execute("INSERT INTO users(id,email,password_hash,role) VALUES (314,'legacy@example.invalid','preserved-hash','USER'),(315,'other@example.invalid','other-hash','USER')");
            sql.execute("INSERT INTO accounts(id,user_id,currency,balance) VALUES (314,314,'KZT',75),(315,315,'KZT',25)");
            sql.execute("UPDATE accounts SET balance=-100 WHERE type='SYSTEM' AND currency='KZT'");
            sql.execute("INSERT INTO transactions(id,type,status,idempotency_key,request_hash,created_by) VALUES (90001,'DEPOSIT','COMPLETED','historical-deposit','legacy',314),(90002,'TRANSFER','COMPLETED','historical-transfer','legacy',314)");
            sql.execute("INSERT INTO ledger_entries(transaction_id,account_id,amount,direction) SELECT 90001,id,100,'DEBIT' FROM accounts WHERE type='SYSTEM' AND currency='KZT'");
            sql.execute("INSERT INTO ledger_entries(transaction_id,account_id,amount,direction) VALUES (90001,314,100,'CREDIT'),(90002,314,25,'DEBIT'),(90002,315,25,'CREDIT')");
            String before = snapshot(c);
            var upgraded = flyway(null);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();
            assertThat(snapshot(c)).isEqualTo(before);
            assertThat(scalar(c, "SELECT count(*) FROM user_identities")).isEqualTo("0");
            sql.execute("INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (314,'https://identity.invalid/realms/esep','opaque-subject','approved-operator')");
            assertSqlState(sql, "INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (315,'https://identity.invalid/realms/esep','opaque-subject','operator')", "23505");
            assertSqlState(sql, "INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (314,'https://identity.invalid/realms/esep','different-subject','operator')", "23505");
            assertSqlState(sql, "INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (999999,'issuer','subject','operator')", "23503");
            assertSqlState(sql, "INSERT INTO user_identities(user_id,issuer,subject,linked_by) VALUES (315,'issuer',' ','operator')", "23514");
            sql.execute("INSERT INTO users(email,password_hash,role) VALUES ('keycloak-only@example.invalid',NULL,'USER')");
            assertThat(scalar(c, "SELECT password_hash FROM users WHERE id=314")).isEqualTo("preserved-hash");
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        if (target != null) config.target(target);
        return config.load();
    }

    private static String snapshot(Connection c) throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        for (String table : new String[]{"users", "accounts", "transactions", "ledger_entries", "outbox_events"}) {
            snapshot.append(scalar(c, "SELECT coalesce(json_agg(t ORDER BY id)::text,'[]') FROM " + table + " t"));
        }
        return snapshot.toString();
    }

    private static String scalar(Connection c, String query) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void assertSqlState(java.sql.Statement sql, String statement, String state) {
        assertThatThrownBy(() -> sql.execute(statement)).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(state));
    }
}
