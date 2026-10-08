package com.esep.transaction;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.CurrentUser;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import com.esep.user.Role;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

import static com.esep.transaction.TestAccounts.KZT;
import static com.esep.transaction.TestAccounts.USD;
import static com.esep.transaction.TestAccounts.systemAccount;
import static com.esep.transaction.TestAccounts.user;
import static com.esep.transaction.TestAccounts.userAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionProcessorTest {

    private static final String KEY = "key-1";
    private static final CurrentUser ME = new CurrentUser(1L, Role.USER);
    private static final CurrentUser ADMIN = new CurrentUser(99L, Role.ADMIN);

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionProcessor processor;

    @Test
    void transfer_valid_savesBalancedTransaction() {
        stubOwner(1L);
        stubLocked(userAccount(1, KZT, "100.0000"), userAccount(2, KZT, "0.0000"));
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResult result = processor.transfer(ME, KEY, transfer(1, 2, "40"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(result.response().entries())
                .extracting(e -> e.accountId(), e -> e.direction())
                .containsExactly(tuple(1L, EntryDirection.DEBIT), tuple(2L, EntryDirection.CREDIT));
    }

    @Test
    void transfer_fromSomebodyElsesAccount_throwsNotFoundWithoutLocking() {
        when(accountRepository.existsByIdAndUser_Id(1L, ME.id())).thenReturn(false);

        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 2, "40")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(accountRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void transfer_fromHigherToLowerId_locksLowerIdFirst() {
        stubOwner(7L);
        stubLocked(userAccount(7, KZT, "100.0000"), userAccount(3, KZT, "0.0000"));
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.transfer(ME, KEY, transfer(7, 3, "10"));

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(3L);
        order.verify(accountRepository).findByIdForUpdate(7L);
    }

    @Test
    void transfer_sameKeySameRequest_returnsOriginalWithoutNewTransaction() {
        Account from = userAccount(1, KZT, "60.0000");
        stubOwner(1L);
        stubLocked(from, userAccount(2, KZT, "40.0000"));
        TransferRequest request = transfer(1, 2, "40");
        when(transactionRepository.findWithEntriesByCreatedBy_IdAndIdempotencyKey(ME.id(), KEY))
                .thenReturn(Optional.of(original(request.fingerprint())));

        TransactionResult result = processor.transfer(ME, KEY, request);

        assertThat(result.replayed()).isTrue();
        assertThat(from.getBalance()).isEqualByComparingTo("60");   // money moved only once
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_sameKeyDifferentRequest_throwsBusinessRule() {
        stubOwner(1L);
        stubLocked(userAccount(1, KZT, "100.0000"), userAccount(2, KZT, "0.0000"));
        when(transactionRepository.findWithEntriesByCreatedBy_IdAndIdempotencyKey(ME.id(), KEY))
                .thenReturn(Optional.of(original(transfer(1, 2, "40").fingerprint())));

        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 2, "41")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different request");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_sameAccount_throwsWithoutLocking() {
        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 1, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("same account");
        verify(accountRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void transfer_unknownTargetAccount_throwsNotFound() {
        stubOwner(1L);
        when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(userAccount(1, KZT, "100.0000")));
        when(accountRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 2, "40")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void transfer_currencyMismatch_throwsBusinessRule() {
        stubOwner(1L);
        stubLocked(userAccount(1, KZT, "100.0000"), userAccount(2, USD, "0.0000"));

        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 2, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Currency mismatch");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_toSystemAccount_throwsBusinessRule() {
        stubOwner(1L);
        stubLocked(userAccount(1, KZT, "100.0000"), systemAccount(2, KZT));

        assertThatThrownBy(() -> processor.transfer(ME, KEY, transfer(1, 2, "40")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only between user accounts");
    }

    @Test
    void deposit_valid_debitsSystemAccountAndLocksInOrder() {
        Account target = userAccount(1, KZT, "0.0000");
        Account funding = systemAccount(100, KZT);
        when(accountRepository.findCurrencyById(1L)).thenReturn(Optional.of(KZT));
        when(accountRepository.findSystemAccountId(KZT)).thenReturn(Optional.of(100L));
        stubLocked(target, funding);
        when(transactionRepository.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.deposit(ADMIN, KEY, new DepositRequest(1L, new BigDecimal("250")));

        assertThat(target.getBalance()).isEqualByComparingTo("250");
        assertThat(funding.getBalance()).isEqualByComparingTo("-250");
        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(1L);
        order.verify(accountRepository).findByIdForUpdate(100L);
    }

    @Test
    void deposit_unsupportedCurrency_throwsBusinessRule() {
        Currency gbp = Currency.getInstance("GBP");
        when(accountRepository.findCurrencyById(1L)).thenReturn(Optional.of(gbp));
        when(accountRepository.findSystemAccountId(gbp)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.deposit(ADMIN, KEY, new DepositRequest(1L, new BigDecimal("10"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void getById_notParticipant_throwsNotFound() {
        when(transactionRepository.isParticipant(5L, ME.id())).thenReturn(false);

        assertThatThrownBy(() -> processor.getById(ME, 5L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).findWithEntriesById(anyLong());
    }

    @Test
    void getById_admin_seesAnyTransaction() {
        when(transactionRepository.findWithEntriesById(5L)).thenReturn(Optional.of(original("hash")));

        assertThat(processor.getById(ADMIN, 5L).type()).isEqualTo(TransactionType.TRANSFER);
        verify(transactionRepository, never()).isParticipant(anyLong(), anyLong());
    }

    private void stubOwner(long fromAccountId) {
        when(accountRepository.existsByIdAndUser_Id(fromAccountId, ME.id())).thenReturn(true);
    }

    private void stubLocked(Account... accounts) {
        for (Account account : accounts) {
            when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        }
    }

    private static LedgerTransaction original(String requestHash) {
        return LedgerTransaction.transfer(user(1), KEY, requestHash,
                userAccount(1, KZT, "100.0000"), userAccount(2, KZT, "0.0000"), new BigDecimal("40"));
    }

    private static TransferRequest transfer(long from, long to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount));
    }
}
