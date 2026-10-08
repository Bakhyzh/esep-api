package com.esep.account;

import com.esep.account.dto.AccountResponse;
import com.esep.account.dto.CreateAccountRequest;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ConflictException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.CurrentUser;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final CurrentUser ME = new CurrentUser(1L, Role.USER);
    private static final CurrentUser OTHER = new CurrentUser(2L, Role.USER);
    private static final CurrentUser ADMIN = new CurrentUser(99L, Role.ADMIN);

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void create_newAccount_hasZeroBalanceAndActiveStatus() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(accountRepository.existsByUser_IdAndCurrencyAndStatus(1L, KZT, AccountStatus.ACTIVE)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.create(ME, new CreateAccountRequest("KZT"));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.currency()).isEqualTo("KZT");
        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void create_unknownUser_throwsNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.create(ME, new CreateAccountRequest("KZT")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_unknownCurrency_throwsBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));

        assertThatThrownBy(() -> accountService.create(ME, new CreateAccountRequest("ABC")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ABC");
    }

    @Test
    void create_activeAccountInSameCurrencyExists_throwsConflict() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(accountRepository.existsByUser_IdAndCurrencyAndStatus(1L, KZT, AccountStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> accountService.create(ME, new CreateAccountRequest("KZT")))
                .isInstanceOf(ConflictException.class);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_uniqueIndexViolatedByParallelRequest_throwsConflict() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(accountRepository.existsByUser_IdAndCurrencyAndStatus(1L, KZT, AccountStatus.ACTIVE)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("ux_accounts_user_currency_active"));

        assertThatThrownBy(() -> accountService.create(ME, new CreateAccountRequest("KZT")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void close_zeroBalance_closesAccount() {
        Account account = new Account(user(1L), KZT);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.close(ME, 10L);

        assertThat(response.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(response.closedAt()).isNotNull();
    }

    @Test
    void close_nonZeroBalance_throwsBusinessRule() {
        Account account = new Account(user(1L), KZT);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal("0.0001"));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.close(ME, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("non-zero balance");
    }

    @Test
    void close_alreadyClosed_throwsBusinessRule() {
        Account account = new Account(user(1L), KZT);
        account.close();
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.close(ME, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void getById_somebodyElsesAccount_throwsNotFound() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(new Account(user(1L), KZT)));

        assertThatThrownBy(() -> accountService.getById(OTHER, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_admin_seesAnyAccount() {
        when(accountRepository.findById(10L)).thenReturn(Optional.of(new Account(user(1L), KZT)));

        assertThat(accountService.getById(ADMIN, 10L).userId()).isEqualTo(1L);
    }

    @Test
    void close_somebodyElsesAccount_throwsNotFoundAndKeepsItOpen() {
        Account account = new Account(user(1L), KZT);
        when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.close(OTHER, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void getByUser_withoutUserId_returnsOwnAccounts() {
        accountService.getByUser(ME, null);

        verify(accountRepository).findAllByUser_IdOrderByIdAsc(1L);
    }

    @Test
    void getByUser_otherUserAsRegularUser_throwsAccessDenied() {
        assertThatThrownBy(() -> accountService.getByUser(ME, 2L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getByUser_otherUserAsAdmin_isAllowed() {
        accountService.getByUser(ADMIN, 2L);

        verify(accountRepository).findAllByUser_IdOrderByIdAsc(2L);
    }

    private static User user(Long id) {
        User user = new User("user" + id + "@esep.dev", "hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
