package com.esep.analytics;

import com.esep.analytics.AnalyticsService.ReportRequest;
import com.esep.analytics.dto.MonthlyComparison;
import com.esep.analytics.dto.MovingAveragePoint;
import com.esep.analytics.dto.SpendingPoint;
import com.esep.analytics.dto.TopTransaction;
import com.esep.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Spending reports of the current user (ADMIN may pass ?userId=).
 * from/to are calendar days (inclusive) in the given zone; default: the last 30 days.
 */
@Tag(name = "Analytics", description = "Spending reports in one currency; dates are calendar days in the given time zone")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final String CURRENCY_REGEX = "^[A-Z]{3}$";

    private final AnalyticsService analyticsService;

    @Operation(summary = "Spending grouped by DAY, WEEK or MONTH")
    @GetMapping("/spending")
    public List<SpendingPoint> spending(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam @Pattern(regexp = CURRENCY_REGEX) String currency,
            @RequestParam(defaultValue = "DAY") Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "UTC") ZoneId zone,
            @RequestParam(required = false) Long userId) {
        return analyticsService.spending(currentUser, new ReportRequest(userId, currency, from, to, zone), period);
    }

    @Operation(summary = "Largest outgoing transfers (DENSE_RANK)")
    @GetMapping("/top-transactions")
    public List<TopTransaction> topTransactions(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam @Pattern(regexp = CURRENCY_REGEX) String currency,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "UTC") ZoneId zone,
            @RequestParam(required = false) Long userId) {
        return analyticsService.top(currentUser, new ReportRequest(userId, currency, from, to, zone), limit);
    }

    @Operation(summary = "Daily spending with a moving average over N calendar days")
    @GetMapping("/moving-average")
    public List<MovingAveragePoint> movingAverage(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam @Pattern(regexp = CURRENCY_REGEX) String currency,
            @RequestParam(defaultValue = "7") @Min(2) @Max(90) int window,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "UTC") ZoneId zone,
            @RequestParam(required = false) Long userId) {
        return analyticsService.movingAverage(currentUser, new ReportRequest(userId, currency, from, to, zone), window);
    }

    @Operation(summary = "Monthly totals compared with the previous month (LAG)")
    @GetMapping("/monthly-comparison")
    public List<MonthlyComparison> monthlyComparison(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam @Pattern(regexp = CURRENCY_REGEX) String currency,
            @RequestParam(defaultValue = "6") @Min(1) @Max(24) int months,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth until,
            @RequestParam(defaultValue = "UTC") ZoneId zone,
            @RequestParam(required = false) Long userId) {
        return analyticsService.monthlyComparison(currentUser, userId, currency, zone, until, months);
    }
}
