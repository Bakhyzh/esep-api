package com.esep.transaction;

import com.esep.common.exception.BusinessRuleException;
import com.esep.transaction.dto.TransactionResponse;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final String KEY = "key-1";
    private static final TransferRequest REQUEST = new TransferRequest(1L, 2L, new BigDecimal("10"));

    @Mock
    private TransactionProcessor processor;

    @InjectMocks
    private TransactionService service;

    @Test
    void transfer_lostUniqueKeyRace_returnsWinnersResult() {
        TransactionResult winner = TransactionResult.replayed(
                new TransactionResponse(5L, TransactionType.TRANSFER, TransactionStatus.COMPLETED, null, List.of()));
        when(processor.transfer(KEY, REQUEST)).thenThrow(new DataIntegrityViolationException("uq_transactions_idempotency_key"));
        when(processor.findReplay(KEY, REQUEST.fingerprint())).thenReturn(Optional.of(winner));

        assertThat(service.transfer(KEY, REQUEST)).isSameAs(winner);
    }

    @Test
    void transfer_integrityViolationNotCausedByKey_isRethrown() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("ck_accounts_balance_non_negative");
        when(processor.transfer(KEY, REQUEST)).thenThrow(error);
        when(processor.findReplay(KEY, REQUEST.fingerprint())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(KEY, REQUEST)).isSameAs(error);
    }

    @Test
    void transfer_businessError_isNotSwallowed() {
        when(processor.transfer(KEY, REQUEST)).thenThrow(new BusinessRuleException("Insufficient funds on account 1"));

        assertThatThrownBy(() -> service.transfer(KEY, REQUEST)).isInstanceOf(BusinessRuleException.class);
    }
}
