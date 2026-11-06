package com.esep.transaction;

import com.esep.account.*;
import com.esep.principal.*;
import com.esep.security.CurrentUser;
import com.esep.support.IntegrationTest;
import com.esep.transaction.dto.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class LedgerDatabaseIntegrationTest extends IntegrationTest {
    static final AtomicLong IDS=new AtomicLong(30000);
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerPrincipalRepository principals;
    @Autowired AccountRepository accounts;
    @Autowired TransactionService money;
    @Autowired TransactionTemplate tx;
    @Autowired Flyway flyway;

    @Test void independentFlywayOwnsOnlyFinancialTablesAndReferenceProjection() {
        flyway.validate();
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'",String.class);
        assertThat(tables).contains("ledger_principals","ledger_identity_bindings","accounts","transactions","ledger_entries","outbox_events")
                .doesNotContain("users","user_identities","notifications","processed_events","user_preferences");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL",String.class)).containsExactly("1");
    }
    @Test void databaseRejectsOrphansOverdraftAndConflictingMappings() {
        assertThatThrownBy(()->jdbc.update("INSERT INTO accounts(user_id,currency) VALUES (-12,'KZT')")).isInstanceOf(DataIntegrityViolationException.class);
        var a=account();
        assertThatThrownBy(()->jdbc.update("UPDATE accounts SET balance=-1 WHERE id=?",a.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO accounts(user_id,currency) VALUES (?,'KZT')",a.getUser().getId())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by) VALUES (?,'issuer',?,'approved')",a.getUser().getId(),"s-"+a.getId());
        assertThatThrownBy(()->jdbc.update("INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by) VALUES (?,'issuer','conflict','approved')",a.getUser().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void rollbackAfterActualPostingsAndOutboxInsertionLeavesEverythingUnchanged() {
        var a=account();var b=account();var admin=new CurrentUser(a.getUser().getId(),Role.ADMIN);
        money.deposit(admin,UUID.randomUUID().toString(),new DepositRequest(a.getId(),new BigDecimal("100")));
        long before=jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class);
        String key=UUID.randomUUID().toString();
        tx.executeWithoutResult(s->{
            money.transfer(new CurrentUser(a.getUser().getId(),Role.USER),key,new TransferRequest(a.getId(),b.getId(),new BigDecimal("25")));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class)).isEqualTo(before+1);
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,a.getId())).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,b.getId())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE idempotency_key=?",Long.class,key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class)).isEqualTo(before);
    }
    Account account() {
        return accounts.save(new Account(principals.save(new LedgerPrincipal(IDS.incrementAndGet())),Currency.getInstance("KZT")));
    }
}
