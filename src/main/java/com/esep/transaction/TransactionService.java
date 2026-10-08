package com.esep.transaction;

import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResponse;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Entry point for controllers. Intentionally NOT @Transactional:
 * it handles the case when two requests with the same Idempotency-Key ran in parallel
 * on different accounts (so they did not wait on each other's locks).
 * The UNIQUE index lets only one INSERT win; the loser's transaction is rolled back,
 * and here, outside of it, we read and return the winner's result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionProcessor processor;

    public TransactionResult transfer(String idempotencyKey, TransferRequest request) {
        try {
            return processor.transfer(idempotencyKey, request);
        } catch (DataIntegrityViolationException e) {
            return replayOrRethrow(idempotencyKey, request.fingerprint(), e);
        }
    }

    public TransactionResult deposit(String idempotencyKey, DepositRequest request) {
        try {
            return processor.deposit(idempotencyKey, request);
        } catch (DataIntegrityViolationException e) {
            return replayOrRethrow(idempotencyKey, request.fingerprint(), e);
        }
    }

    public TransactionResponse getById(Long id) {
        return processor.getById(id);
    }

    private TransactionResult replayOrRethrow(String idempotencyKey, String requestHash,
                                              DataIntegrityViolationException e) {
        // not every integrity error is a key race (e.g. a CHECK constraint): replay only if the key exists now
        TransactionResult result = processor.findReplay(idempotencyKey, requestHash).orElseThrow(() -> e);
        log.info("Idempotency-Key race resolved by replay, key={}", idempotencyKey);
        return result;
    }
}
