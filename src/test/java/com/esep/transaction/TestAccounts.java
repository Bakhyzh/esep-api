package com.esep.transaction;

import com.esep.account.Account;
import com.esep.account.AccountType;
import com.esep.user.Role;
import com.esep.user.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Currency;

final class TestAccounts {

    static final Currency KZT = Currency.getInstance("KZT");
    static final Currency USD = Currency.getInstance("USD");

    private TestAccounts() {
    }

    static Account userAccount(long id, Currency currency, String balance) {
        Account account = new Account(user(id), currency);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        return account;
    }

    static Account systemAccount(long id, Currency currency) {
        Account account = userAccount(id, currency, "0.0000");
        ReflectionTestUtils.setField(account, "type", AccountType.SYSTEM);
        return account;
    }

    private static User user(long id) {
        User user = new User("user" + id + "@esep.dev", "hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
