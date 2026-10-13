package com.esep.transaction;

import com.esep.account.AccountRepository;
import com.esep.common.exception.InvalidRequestException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.common.web.PageResponse;
import com.esep.security.CurrentUser;
import com.esep.transaction.OperationHistoryRepository.HistoryFilter;
import com.esep.transaction.dto.OperationResponse;
import com.esep.user.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void somebodyElsesAccount_isNotFoundBeforeAnyQuery() {
        when(accountRepository.existsByIdAndUser_Id(50L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.history(ME, 50L, null, null, UTC, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void calendarDaysBecomeInstantsInTheRequestedZone_toIsInclusive() {
        when(repository.count(any())).thenReturn(0L);

        service.history(ME, null, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"),
                ZoneId.of("Asia/Tokyo"), 0, 20);

        ArgumentCaptor<HistoryFilter> filter = ArgumentCaptor.forClass(HistoryFilter.class);
        verify(repository).count(filter.capture());
        // Tokyo is UTC+9 without DST: midnight there is 15:00 UTC the day before; "to" is the start of April 1
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-02-28T15:00:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-03-31T15:00:00Z"));
        assertThat(filter.getValue().userId()).isEqualTo(1L);
    }

    @Test
    void emptyHistory_skipsThePageQuery() {
        when(repository.count(any())).thenReturn(0L);

        PageResponse<OperationResponse> page = service.history(ME, null, null, null, UTC, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
        verify(repository, never()).find(any(), anyInt(), anyInt());
    }
}
