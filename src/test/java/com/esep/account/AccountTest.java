package com.esep.account;

import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ErrorCode;
import com.esep.user.Role;
import com.esep.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Test
    void creditOverTheColumnLimit_isABusinessErrorAndKeepsTheBalance() {
        Account account = account(AccountType.USER, "999999999999999.0000");

        assertThatThrownBy(() -> account.credit(new BigDecimal("1")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
        assertThat(account.getBalance()).isEqualByComparingTo("999999999999999");
    }

    @Test
    void creditUpToTheLimit_isAllowed() {
        Account account = account(AccountType.USER, "999999999999999.0000");

        account.credit(new BigDecimal("0.9999"));

        assertThat(account.getBalance()).isEqualByComparingTo(Account.BALANCE_LIMIT);
    }

    @Test
    void systemAccountDebitBelowMinusLimit_isABusinessError() {
        Account system = account(AccountType.SYSTEM, "-999999999999999.0000");

        assertThatThrownBy(() -> system.debit(new BigDecimal("1")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
    }

    @Test
    void systemAccount_cannotBeClosed() {
        Account system = account(AccountType.SYSTEM, "-500.0000");

        assertThatThrownBy(system::close)
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo(ErrorCode.SYSTEM_ACCOUNT_OPERATION);
        assertThat(system.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void closing_recordsTheMomentAndBlocksFurtherPostings() {
        Account account = account(AccountType.USER, "0.0000");

        account.close();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isNotNull();
        assertThatThrownBy(() -> account.credit(BigDecimal.ONE))
                .extracting("code").isEqualTo(ErrorCode.ACCOUNT_CLOSED);
    }

    @Test
    void newAccount_startsEmptyWithMoneyScale() {
        Account account = new Account(new User("u@esep.dev", "hash", Role.USER), KZT);

        assertThat(account.getBalance()).hasToString("0.0000");
        assertThat(account.getType()).isEqualTo(AccountType.USER);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    static Account account(AccountType type, String balance) {
        User user = new User("u@esep.dev", "hash", Role.USER);
        Account account = new Account(user, KZT);
        ReflectionTestUtils.setField(account, "id", 7L);
        ReflectionTestUtils.setField(account, "type", type);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        return account;
    }
}
