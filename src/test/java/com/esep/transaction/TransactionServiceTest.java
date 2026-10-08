package com.esep.transaction;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.account.AccountStatus;
import com.esep.account.AccountType;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ConflictException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResponse;
import com.esep.transaction.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

import static com.esep.transaction.TestAccounts.KZT;
import static com.esep.transaction.TestAccounts.USD;
import static com.esep.transaction.TestAccounts.systemAccount;
import static com.esep.transaction.TestAccounts.userAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final String KEY = "key-1";

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void transfer_valid_savesBalancedTransaction() {
        stubAccounts(userAccount(1, KZT, "100.0000"), userAccount(2, KZT, "0.0000"));
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response = transactionService.transfer(KEY, transfer(1, 2, "40"));

        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.entries())
                .extracting(TransactionResponse.Entry::accountId, TransactionResponse.Entry::direction)
                .containsExactly(
                        tuple(1L, EntryDirection.DEBIT),
                        tuple(2L, EntryDirection.CREDIT));
    }

    @Test
    void transfer_repeatedIdempotencyKey_throwsConflict() {
        when(transactionRepository.existsByIdempotencyKey(KEY)).thenReturn(true);

        assertThatThrownBy(() -> transactionService.transfer(KEY, transfer(1, 2, "40")))
                .isInstanceOf(ConflictException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_sameAccount_throwsBusinessRule() {
        assertThatThrownBy(() -> transactionService.transfer(KEY, transfer(1, 1, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("same account");
    }

    @Test
    void transfer_unknownAccount_throwsNotFound() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(KEY, transfer(1, 2, "40")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void transfer_currencyMismatch_throwsBusinessRule() {
        stubAccounts(userAccount(1, KZT, "100.0000"), userAccount(2, USD, "0.0000"));

        assertThatThrownBy(() -> transactionService.transfer(KEY, transfer(1, 2, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Currency mismatch");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_fromSystemAccount_throwsBusinessRule() {
        stubAccounts(systemAccount(1, KZT), userAccount(2, KZT, "0.0000"));

        assertThatThrownBy(() -> transactionService.transfer(KEY, transfer(1, 2, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only between user accounts");
    }

    @Test
    void deposit_valid_debitsSystemAccount() {
        Account target = userAccount(1, KZT, "0.0000");
        Account funding = systemAccount(100, KZT);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(target));
        when(accountRepository.findByTypeAndCurrencyAndStatus(AccountType.SYSTEM, KZT, AccountStatus.ACTIVE))
                .thenReturn(Optional.of(funding));
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transactionService.deposit(KEY, new DepositRequest(1L, new BigDecimal("250")));

        assertThat(target.getBalance()).isEqualByComparingTo("250");
        assertThat(funding.getBalance()).isEqualByComparingTo("-250");
    }

    @Test
    void deposit_unsupportedCurrency_throwsBusinessRule() {
        Currency gbp = Currency.getInstance("GBP");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(userAccount(1, gbp, "0.0000")));
        when(accountRepository.findByTypeAndCurrencyAndStatus(AccountType.SYSTEM, gbp, AccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.deposit(KEY, new DepositRequest(1L, new BigDecimal("10"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not supported");
    }

    private void stubAccounts(Account from, Account to) {
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
    }

    private static TransferRequest transfer(long from, long to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount));
    }
}
