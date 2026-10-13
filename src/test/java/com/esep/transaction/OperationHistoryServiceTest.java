package com.esep.transaction;

import com.esep.account.AccountRepository;
import com.esep.common.exception.InvalidRequestException;
import com.esep.security.CurrentUser;
import com.esep.user.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OperationHistoryServiceTest {

    private static final CurrentUser ME = new CurrentUser(1L, Role.USER);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock
    private OperationHistoryRepository repository;
    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private OperationHistoryService service;

    @Test
    void dateBeyondWhatPostgresCanStore_isInvalidRequest() {
        LocalDate farFuture = LocalDate.parse("+300000-01-01");

        assertThatThrownBy(() -> service.history(ME, null, farFuture, null, UTC, 0, 20))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.history(ME, null, null, LocalDate.MAX, UTC, 0, 20))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(repository);
    }
}
