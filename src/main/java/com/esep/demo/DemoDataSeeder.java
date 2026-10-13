package com.esep.demo;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.account.AccountService;
import com.esep.account.AccountStatus;
import com.esep.account.dto.AccountResponse;
import com.esep.account.dto.CreateAccountRequest;
import com.esep.security.CurrentUser;
import com.esep.transaction.TransactionService;
import com.esep.transaction.dto.DepositRequest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Creates the demo user for a public demo, only with DEMO_USER_ENABLED=true.
 * Unlike the dev seed migration, the credentials are not in the code: they come from
 * DEMO_USER_EMAIL / DEMO_USER_PASSWORD. Safe to run on every start:
 * <ul>
 *   <li>user missing: created (role USER); password changed in the environment: hash updated;</li>
 *   <li>user without accounts: one account is opened and funded once (the deposit's
 *       Idempotency-Key is derived from the account id, so it is never applied twice);</li>
 *   <li>first account still active: the deposit is repeated with the same key, which only
 *       replays it, unless a previous start crashed before funding.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "esep.demo", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DemoProperties.class)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final DemoProperties properties;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        User user = upsertUser();
        List<Account> accounts = accountRepository.findAllByUser_IdOrderByIdAsc(user.getId());
        if (accounts.isEmpty()) {
            fund(user, openAccount(user));
        } else if (accounts.getFirst().getStatus() == AccountStatus.ACTIVE) {
            // the account and the deposit are two transactions: if the last start died between them,
            // the account exists but was never funded. The deposit is idempotent by its key,
            // so for an already funded account this is only a replay and adds nothing.
            fund(user, accounts.getFirst().getId());
        }
        // the email is fine to log, the password never is
        log.info("Demo user {} is ready", user.getEmail());
    }

    private User upsertUser() {
        String email = properties.email().trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(email)
                .map(existing -> {
                    if (!passwordEncoder.matches(properties.password(), existing.getPasswordHash())) {
                        existing.changePasswordHash(passwordEncoder.encode(properties.password()));
                        return userRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        new User(email, passwordEncoder.encode(properties.password()), Role.USER)));
    }

    private long openAccount(User user) {
        AccountResponse account = accountService.create(
                new CurrentUser(user.getId(), Role.USER), new CreateAccountRequest(properties.currency()));
        return account.id();
    }

    private void fund(User user, long accountId) {
        if (properties.initialBalance().signum() > 0) {
            // deposits are an ADMIN operation; the seeder acts as the system on the demo user's behalf
            transactionService.deposit(new CurrentUser(user.getId(), Role.ADMIN), "demo-seed-" + accountId,
                    new DepositRequest(accountId, properties.initialBalance()));
        }
    }
}
