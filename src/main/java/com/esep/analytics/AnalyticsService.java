package com.esep.analytics;

import com.esep.analytics.AnalyticsRepository.ReportScope;
import com.esep.analytics.dto.MonthlyComparison;
import com.esep.analytics.dto.MovingAveragePoint;
import com.esep.analytics.dto.SpendingPoint;
import com.esep.analytics.dto.TopTransaction;
import com.esep.common.exception.InvalidRequestException;
import com.esep.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;

    private final AnalyticsRepository repository;
    private final AnalyticsCache cache;

    public List<SpendingPoint> spending(CurrentUser currentUser, ReportRequest request, Period period) {
        ReportScope scope = scope(currentUser, request);
        return cache.getOrLoad(scope.userId(), "spending", cacheParams(scope, period), SpendingPoint.class,
                () -> repository.spendingByPeriod(scope, period));
    }

    public List<TopTransaction> top(CurrentUser currentUser, ReportRequest request, int limit) {
        ReportScope scope = scope(currentUser, request);
        return cache.getOrLoad(scope.userId(), "top", cacheParams(scope, limit), TopTransaction.class,
                () -> repository.topTransactions(scope, limit));
    }

    public List<MovingAveragePoint> movingAverage(CurrentUser currentUser, ReportRequest request, int window) {
        ReportScope scope = scope(currentUser, request);
        return cache.getOrLoad(scope.userId(), "moving-average", cacheParams(scope, window), MovingAveragePoint.class,
                () -> repository.movingAverage(scope, window));
    }

    public List<MonthlyComparison> monthlyComparison(CurrentUser currentUser, Long userId, String currency,
                                                     ZoneId zone, YearMonth until, int months) {
        ZoneId validZone = requireRegionZone(zone);
        YearMonth lastMonth = until != null ? until : YearMonth.now(validZone);
        YearMonth firstMonth = lastMonth.minusMonths(months - 1L);
        ReportScope scope = new ReportScope(owner(currentUser, userId), currency,
                firstMonth.atDay(1), lastMonth.atEndOfMonth(), validZone);
        return cache.getOrLoad(scope.userId(), "monthly", cacheParams(scope, months), MonthlyComparison.class,
                () -> repository.monthlyComparison(scope));
    }

    // built from the RESOLVED scope (defaults applied): "last 30 days" asked today and tomorrow
    // are different keys, so a default range never serves yesterday's window
    private static String cacheParams(ReportScope scope, Object extra) {
        return String.join(":", scope.currency(), scope.fromDay().toString(), scope.toDay().toString(),
                scope.zone().getId(), String.valueOf(extra));
    }

    private ReportScope scope(CurrentUser currentUser, ReportRequest request) {
        ZoneId zone = requireRegionZone(request.zone());
        LocalDate to = request.to() != null ? request.to() : LocalDate.now(zone);
        LocalDate from = request.from() != null ? request.from() : to.minusDays(DEFAULT_DAYS - 1);
        if (from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new InvalidRequestException("Date range must not exceed " + MAX_DAYS + " days");
        }
        return new ReportScope(owner(currentUser, request.userId()), request.currency(), from, to, zone);
    }

    private static Long owner(CurrentUser currentUser, Long userId) {
        Long ownerId = userId != null ? userId : currentUser.id();
        if (!currentUser.canAccess(ownerId)) {
            throw new AccessDeniedException("You can only see your own analytics");
        }
        return ownerId;
    }

    /**
     * Only region ids like "Asia/Almaty". PostgreSQL reads offsets in AT TIME ZONE as POSIX,
     * where the sign is inverted: '+05:00' would silently mean UTC-5.
     */
    private static ZoneId requireRegionZone(ZoneId zone) {
        if (zone instanceof ZoneOffset && !zone.equals(ZoneOffset.UTC)) {
            throw new InvalidRequestException("Use a region time zone id like Asia/Almaty, not an offset");
        }
        return zone.normalized().equals(ZoneOffset.UTC) ? ZoneId.of("UTC") : zone;
    }

    /** Common report parameters; from/to are calendar days in {@code zone}, both inclusive. */
    public record ReportRequest(Long userId, String currency, LocalDate from, LocalDate to, ZoneId zone) {
    }
}
