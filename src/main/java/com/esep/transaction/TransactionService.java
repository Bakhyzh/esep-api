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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final LedgerTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    /**
     * One DB transaction: two balance updates + transaction row + two entries.
     * Any exception (insufficient funds, closed account, DB error) rolls back all of it.
     * Locking and safe retries with the same key come in stage 4.
     */
    @Transactional
    public TransactionResponse transfer(String idempotencyKey, TransferRequest request) {
        requireNewKey(idempotencyKey);
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BusinessRuleException("Cannot transfer to the same account");
        }

        Account from = findAccount(request.fromAccountId());
        Account to = findAccount(request.toAccountId());
        if (from.isSystem() || to.isSystem()) {
            throw new BusinessRuleException("Transfers are allowed only between user accounts");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BusinessRuleException("Currency mismatch: " + from.getCurrency() + " -> " + to.getCurrency());
        }

        LedgerTransaction tx = LedgerTransaction.transfer(idempotencyKey, from, to, request.amount());
        // accounts are managed entities: their new balances are flushed by dirty checking on commit
        return TransactionResponse.from(transactionRepository.save(tx));
    }

    @Transactional
    public TransactionResponse deposit(String idempotencyKey, DepositRequest request) {
        requireNewKey(idempotencyKey);
        Account target = findAccount(request.accountId());
        if (target.isSystem()) {
            throw new BusinessRuleException("Cannot deposit to a system account");
        }
        Account funding = accountRepository
                .findByTypeAndCurrencyAndStatus(AccountType.SYSTEM, target.getCurrency(), AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessRuleException("Deposits in " + target.getCurrency() + " are not supported"));

        LedgerTransaction tx = LedgerTransaction.deposit(idempotencyKey, funding, target, request.amount());
        return TransactionResponse.from(transactionRepository.save(tx));
    }

    public TransactionResponse getById(Long id) {
        return transactionRepository.findWithEntriesById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    // stage 3: a repeated key is rejected; stage 4 will return the original result instead
    private void requireNewKey(String idempotencyKey) {
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) {
            throw new ConflictException("Transaction with this Idempotency-Key already exists");
        }
    }

    private Account findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }
}
