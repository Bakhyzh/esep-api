package com.esep.analytics;

import com.esep.analytics.AnalyticsRepository.ReportScope;
import com.esep.analytics.AnalyticsService.ReportRequest;
import com.esep.common.exception.InvalidRequestException;
import com.esep.security.CurrentUser;
import com.esep.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final CurrentUser ME = new CurrentUser(1L, Role.USER);
    private static final CurrentUser ADMIN = new CurrentUser(99L, Role.ADMIN);
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    @Mock
    private AnalyticsRepository repository;

    @Mock
    private AnalyticsCache cache;

    @InjectMocks
    private AnalyticsService service;

    @BeforeEach
    void cacheAlwaysMisses() {
        lenient().when(cache.getOrLoad(anyLong(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(4).get());
    }

    @Test
    void noDates_defaultsToLast30DaysOfTheCurrentUser() {
        service.spending(ME, new ReportRequest(null, "KZT", null, null, ALMATY), Period.DAY);

        ReportScope scope = capturedSpendingScope();
        assertThat(scope.userId()).isEqualTo(1L);
        assertThat(scope.toDay()).isEqualTo(LocalDate.now(ALMATY));
        assertThat(scope.fromDay()).isEqualTo(scope.toDay().minusDays(29));
    }

    @Test
    void fromAfterTo_isRejected() {
        ReportRequest request = new ReportRequest(null, "KZT", LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-01"), ALMATY);

        assertThatThrownBy(() -> service.spending(ME, request, Period.DAY))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rangeLongerThanOneYear_isRejected() {
        ReportRequest request = new ReportRequest(null, "KZT", LocalDate.parse("2025-01-01"), LocalDate.parse("2026-03-01"), ALMATY);

        assertThatThrownBy(() -> service.top(ME, request, 10))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("366");
    }

    @Test
    void offsetZone_isRejectedBecausePostgresInvertsItsSign() {
        ReportRequest request = new ReportRequest(null, "KZT", null, null, ZoneOffset.ofHours(5));

        assertThatThrownBy(() -> service.spending(ME, request, Period.DAY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Asia/Almaty");
    }

    @Test
    void utcOffsetZ_isAcceptedAsUtc() {
        service.spending(ME, new ReportRequest(null, "KZT", null, null, ZoneOffset.UTC), Period.DAY);

        assertThat(capturedSpendingScope().zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void regularUserAskingForSomebodyElse_isDenied() {
        assertThatThrownBy(() -> service.spending(ME, new ReportRequest(2L, "KZT", null, null, ALMATY), Period.DAY))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminMayAskForAnyUser() {
        service.spending(ADMIN, new ReportRequest(2L, "KZT", null, null, ALMATY), Period.DAY);

        assertThat(capturedSpendingScope().userId()).isEqualTo(2L);
    }

    @Test
    void monthlyComparison_coversWholeMonthsEndingAtUntil() {
        service.monthlyComparison(ME, null, "KZT", ALMATY, YearMonth.of(2026, 4), 3);

        ArgumentCaptor<ReportScope> captor = ArgumentCaptor.forClass(ReportScope.class);
        verify(repository).monthlyComparison(captor.capture());
        assertThat(captor.getValue().fromDay()).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(captor.getValue().toDay()).isEqualTo(LocalDate.parse("2026-04-30"));
    }

    private ReportScope capturedSpendingScope() {
        ArgumentCaptor<ReportScope> captor = ArgumentCaptor.forClass(ReportScope.class);
        verify(repository).spendingByPeriod(captor.capture(), eq(Period.DAY));
        return captor.getValue();
    }

    @Test
    void cacheKeyContainsResolvedDatesAndReportParameters() {
        ReportRequest request = new ReportRequest(null, "KZT", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"), ALMATY);

        service.top(ME, request, 5);

        verify(cache).getOrLoad(eq(1L), eq("top"), eq("KZT:2026-03-01:2026-03-31:Asia/Almaty:5"), any(), any());
    }
}
