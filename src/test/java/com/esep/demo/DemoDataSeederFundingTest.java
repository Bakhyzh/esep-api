package com.esep.demo;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.account.AccountService;
import com.esep.security.CurrentUser;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.DepositRequest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Restarts after a crash: the deposit is retried by its idempotency key, never duplicated. */
@ExtendWith(MockitoExtension.class)
class DemoDataSeederFundingTest {

    private static final String EMAIL = "demo@esep.dev";

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountService accountService;
    @Mock
    private TransactionService transactionService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private DemoDataSeeder seeder;
    private User user;

    @BeforeEach
    void setUp() {
        DemoProperties properties = new DemoProperties(true, EMAIL, "demo-password", "KZT", new BigDecimal("5000"));
        seeder = new DemoDataSeeder(properties, userRepository, accountRepository, accountService,
                transactionService, passwordEncoder);
        user = new User(EMAIL, "{bcrypt}hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", 3L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
    }

    @Test
    void existingActiveAccount_isFundedWithTheSameKeyAsOnTheFirstStart() {
        when(accountRepository.findAllByUser_IdOrderByIdAsc(3L)).thenReturn(List.of(account(7L)));

        seeder.run(new DefaultApplicationArguments());

        verify(transactionService).deposit(any(CurrentUser.class), eq("demo-seed-7"),
                eq(new DepositRequest(7L, new BigDecimal("5000"))));
        verify(accountService, never()).create(any(), any());
    }

    @Test
    void closedFirstAccount_isLeftAlone() {
        Account closed = account(7L);
        closed.close();
        when(accountRepository.findAllByUser_IdOrderByIdAsc(3L)).thenReturn(List.of(closed));

        seeder.run(new DefaultApplicationArguments());

        verify(transactionService, never()).deposit(any(), anyString(), any());
    }

    private Account account(long id) {
        Account account = new Account(user, Currency.getInstance("KZT"));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
