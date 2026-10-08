package com.esep.transaction;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.common.exception.BusinessRuleException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.security.CurrentUser;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResponse;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import com.esep.user.User;
import com.esep.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Does the actual work inside ONE database transaction.
 * Separate bean from {@link TransactionService} on purpose: the service must catch
 * exceptions AFTER this transaction has rolled back (a call to this.method() would
 * bypass the Spring proxy and @Transactional would be ignored).
 */
@Service
@RequiredArgsConstructor
public class TransactionProcessor {

    private final LedgerTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    /** Money can be sent only from your own account (an admin too), to any account. */
    @Transactional
    public TransactionResult transfer(CurrentUser currentUser, String idempotencyKey, TransferRequest request) {
        String requestHash = request.fingerprint();
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BusinessRuleException("Cannot transfer to the same account");
        }
        // 0. ownership before locking: nobody can hold locks on other people's accounts.
        //    The owner of an account never changes, so checking it without a lock is safe.
        //    404 instead of 403: do not reveal that somebody else's account exists.
        if (!accountRepository.existsByIdAndUser_Id(request.fromAccountId(), currentUser.id())) {
            throw new ResourceNotFoundException("Account", request.fromAccountId());
        }

        // 1. lock both rows, always in ascending id order -> no deadlocks between A->B and B->A
        Map<Long, Account> locked = lockInIdOrder(request.fromAccountId(), request.toAccountId());

        // 2. check the key only AFTER the locks: a parallel request with the same key and the same
        //    accounts has been waiting on the lock and now sees the committed original
        Optional<TransactionResult> replay = findReplay(currentUser, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Account from = locked.get(request.fromAccountId());
        Account to = locked.get(request.toAccountId());
        if (from.isSystem() || to.isSystem()) {
            throw new BusinessRuleException("Transfers are allowed only between user accounts");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BusinessRuleException("Currency mismatch: " + from.getCurrency() + " -> " + to.getCurrency());
        }

        // 3. balances are checked on locked, fresh rows: nobody can change them until we commit
        LedgerTransaction tx = LedgerTransaction.transfer(
                creator(currentUser), idempotencyKey, requestHash, from, to, request.amount());
        return TransactionResult.created(TransactionResponse.from(transactionRepository.save(tx)));
    }

    /** ADMIN only, enforced by the URL rule in SecurityConfig. */
    @Transactional
    public TransactionResult deposit(CurrentUser currentUser, String idempotencyKey, DepositRequest request) {
        String requestHash = request.fingerprint();
        // currency never changes, so reading it without a lock is safe
        var currency = accountRepository.findCurrencyById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));
        Long fundingId = accountRepository.findSystemAccountId(currency)
                .orElseThrow(() -> new BusinessRuleException("Deposits in " + currency + " are not supported"));

        Map<Long, Account> locked = lockInIdOrder(fundingId, request.accountId());

        Optional<TransactionResult> replay = findReplay(currentUser, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Account target = locked.get(request.accountId());
        if (target.isSystem()) {
            throw new BusinessRuleException("Cannot deposit to a system account");
        }
        LedgerTransaction tx = LedgerTransaction.deposit(
                creator(currentUser), idempotencyKey, requestHash, locked.get(fundingId), target, request.amount());
        return TransactionResult.created(TransactionResponse.from(transactionRepository.save(tx)));
    }

    /** Used by {@link TransactionService} after a unique-key race, in a fresh transaction. */
    @Transactional(readOnly = true)
    public Optional<TransactionResult> findReplay(CurrentUser currentUser, String idempotencyKey, String requestHash) {
        return transactionRepository.findWithEntriesByCreatedBy_IdAndIdempotencyKey(currentUser.id(), idempotencyKey)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new BusinessRuleException("Idempotency-Key was already used for a different request");
                    }
                    return TransactionResult.replayed(TransactionResponse.from(existing));
                });
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(CurrentUser currentUser, Long id) {
        if (!currentUser.isAdmin() && !transactionRepository.isParticipant(id, currentUser.id())) {
            throw new ResourceNotFoundException("Transaction", id);
        }
        return transactionRepository.findWithEntriesById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    // a reference (proxy) is enough for the created_by foreign key: no SELECT from users
    private User creator(CurrentUser currentUser) {
        return userRepository.getReferenceById(currentUser.id());
    }

    private Map<Long, Account> lockInIdOrder(Long... ids) {
        Map<Long, Account> locked = new HashMap<>();
        Stream.of(ids).sorted().forEach(id -> locked.put(id, accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id))));
        return locked;
    }
}
